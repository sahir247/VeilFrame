package com.veilframe.app.pdf;

import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSObject;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Conservative structural/revision checks for signed PDFs.
 *
 * This is deliberately not described as a complete ISO 32000 validator: PDFBox's
 * object model cannot by itself prove absence of all shadow-attack constructions.
 */
public final class PdfSignatureValidation {
    public enum State { PASS, FAIL, REVIEW_REQUIRED }

    public static final class Check {
        public final String name;
        public final State state;
        public final String detail;
        Check(String name, State state, String detail) {
            this.name = name;
            this.state = state;
            this.detail = detail;
        }
    }

    public static final class Report {
        public final List<Check> checks;
        public final boolean safeToAccept;
        Report(List<Check> checks) {
            this.checks = Collections.unmodifiableList(new ArrayList<>(checks));
            boolean pass = true;
            for (Check check : checks) if (check.state != State.PASS) pass = false;
            safeToAccept = pass;
        }
        public boolean hasFailedChecks() {
            for (Check check : checks) {
                if (check.state == State.FAIL) return true;
            }
            return false;
        }
        public String toString() {
            StringBuilder out = new StringBuilder();
            for (Check check : checks) {
                out.append(check.name).append(": ").append(check.state.name())
                        .append(" — ").append(check.detail).append("\n");
            }
            return out.toString();
        }
    }

    private PdfSignatureValidation() { }

    public static Report inspect(byte[] pdf, PDSignature signature, int[] byteRange) {
        return inspect(pdf, signature, byteRange, null);
    }

    public static Report inspect(byte[] pdf, PDSignature signature, int[] byteRange, String password) {
        List<Check> checks = new ArrayList<>();
        if (pdf == null || signature == null || byteRange == null || byteRange.length != 4) {
            checks.add(new Check("PDF revision structure", State.FAIL, "PDF, signature, or four-element ByteRange is missing."));
            return new Report(checks);
        }

        long a = byteRange[0], b = byteRange[1], c = byteRange[2], d = byteRange[3];
        long end = c + d;
        boolean rangeOk = a == 0 && b > 0 && c > b && d > 0
                && b <= pdf.length && c <= pdf.length && end >= c && end <= pdf.length;
        if (!rangeOk) {
            checks.add(new Check("ByteRange bounds", State.FAIL, "Offsets overlap, overflow, or extend beyond the selected PDF."));
            return new Report(checks);
        }
        checks.add(new Check("ByteRange bounds", State.PASS, "Four ranges are ordered and inside the selected PDF."));

        int gapStart = (int) b;
        int gapEnd = (int) c;
        while (gapStart < gapEnd && whitespace(pdf[gapStart])) gapStart++;
        while (gapEnd > gapStart && whitespace(pdf[gapEnd - 1])) gapEnd--;
        boolean contentsGap = gapEnd - gapStart >= 2 && pdf[gapStart] == '<' && pdf[gapEnd - 1] == '>';
        boolean hexContents = contentsGap;
        if (contentsGap) {
            for (int i = gapStart + 1; i < gapEnd - 1; i++) {
                byte ch = pdf[i];
                if (!whitespace(ch) && !isHex(ch)) {
                    hexContents = false;
                    break;
                }
            }
        }
        checks.add(new Check("Excluded signature Contents gap",
                hexContents ? State.PASS : State.FAIL,
                hexContents ? "The excluded ByteRange gap is a PDF hexadecimal string with only hex digits/whitespace."
                        : "The excluded gap is not a valid PDF hexadecimal Contents string."));

        if (end < pdf.length) {
            checks.add(new Check("Current-file coverage", State.REVIEW_REQUIRED,
                    "The signature covers an earlier revision; " + (pdf.length - end)
                            + " trailing bytes require incremental-update and permission analysis."));
        } else {
            checks.add(new Check("Current-file coverage", State.PASS,
                    "The signed byte range reaches the current end of file."));
        }

        if (end > Integer.MAX_VALUE) {
            checks.add(new Check("Signed-revision parse", State.REVIEW_REQUIRED,
                    "Signed revision is too large for this Android implementation to safely slice."));
        } else {
            byte[] revision = new byte[(int) end];
            System.arraycopy(pdf, 0, revision, 0, revision.length);
            try (PDDocument signedDocument = (password != null && !password.isEmpty())
                    ? PDDocument.load(new ByteArrayInputStream(revision), password)
                    : PDDocument.load(new ByteArrayInputStream(revision))) {
                List<PDSignature> signatures = signedDocument.getSignatureDictionaries();
                boolean matchingSignature = false;
                if (signatures != null) {
                    for (PDSignature candidate : signatures) {
                        int[] candidateRange = candidate.getByteRange();
                        if (!sameByteRange(byteRange, candidateRange)) continue;
                        try {
                            // A different signature dictionary in the prefix is not sufficient:
                            // match this exact ByteRange and the excluded CMS Contents bytes.
                            if (java.util.Arrays.equals(signature.getContents(pdf), candidate.getContents(revision))) {
                                matchingSignature = true;
                                break;
                            }
                        } catch (Exception ignored) {
                            // Treat an unreadable candidate as non-matching, never as a pass.
                        }
                    }
                }
                checks.add(new Check("Signed-revision signature identity",
                        matchingSignature ? State.PASS : State.FAIL,
                        matchingSignature
                                ? "The signed-revision prefix contains this exact signature ByteRange and Contents value."
                                : "The signed-revision prefix parsed, but this exact signature dictionary was not matched."));
            } catch (InvalidPasswordException e) {
                checks.add(new Check("Signed-revision parse", State.REVIEW_REQUIRED,
                        "The signed revision is password-protected and requires document authentication."));
            } catch (Exception e) {
                checks.add(new Check("Signed-revision parse", State.FAIL,
                        "The PDF parser could not load the exact signed revision (" + e.getClass().getSimpleName() + ")."));
            }
        }

        inspectTransformReferences(signature, checks);
        checks.add(new Check("Shadow-attack / semantic revision analysis", State.REVIEW_REQUIRED,
                "A parser load and transform-reference inspection cannot prove that all hidden content or shadow attacks are absent."));
        return new Report(checks);
    }

    private static boolean sameByteRange(int[] left, int[] right) {
        if (left == null || right == null || left.length != 4 || right.length != 4) return false;
        for (int i = 0; i < 4; i++) if (left[i] != right[i]) return false;
        return true;
    }

    private static void inspectTransformReferences(PDSignature signature, List<Check> checks) {
        COSDictionary dictionary = signature.getCOSObject();
        COSArray references = dictionary.getCOSArray(COSName.getPDFName("Reference"));
        if (references == null) {
            checks.add(new Check("DocMDP / FieldMDP transform references", State.REVIEW_REQUIRED,
                    "No /Reference array is present; the app has not established whether a certification or field-lock policy applies."));
            return;
        }
        boolean sawDocMdp = false;
        boolean sawFieldMdp = false;
        boolean malformed = false;
        for (int i = 0; i < references.size(); i++) {
            COSBase item = dereference(references.get(i));
            if (!(item instanceof COSDictionary)) { malformed = true; continue; }
            COSDictionary reference = (COSDictionary) item;
            COSBase methodBase = dereference(reference.getDictionaryObject(COSName.getPDFName("TransformMethod")));
            if (!(methodBase instanceof COSName)) { malformed = true; continue; }
            String method = ((COSName) methodBase).getName();
            if ("DocMDP".equals(method)) {
                sawDocMdp = true;
                COSBase paramsBase = dereference(reference.getDictionaryObject(COSName.getPDFName("TransformParams")));
                if (!(paramsBase instanceof COSDictionary)) { malformed = true; continue; }
                COSBase pBase = dereference(((COSDictionary) paramsBase).getDictionaryObject(COSName.getPDFName("P")));
                if (!(pBase instanceof com.tom_roush.pdfbox.cos.COSNumber)) { malformed = true; continue; }
                com.tom_roush.pdfbox.cos.COSNumber permissionNumber =
                        (com.tom_roush.pdfbox.cos.COSNumber) pBase;
                int permission = permissionNumber.intValue();
                if (permissionNumber.floatValue() != permission || permission < 1 || permission > 3) malformed = true;
            } else if ("FieldMDP".equals(method)) {
                sawFieldMdp = true;
                COSBase paramsBase = dereference(reference.getDictionaryObject(COSName.getPDFName("TransformParams")));
                if (!(paramsBase instanceof COSDictionary)) { malformed = true; continue; }
                COSDictionary params = (COSDictionary) paramsBase;
                COSBase actionBase = dereference(params.getDictionaryObject(COSName.getPDFName("Action")));
                if (!(actionBase instanceof COSName)) { malformed = true; continue; }
                String action = ((COSName) actionBase).getName();
                if (!("All".equals(action) || "Include".equals(action) || "Exclude".equals(action))) {
                    malformed = true;
                    continue;
                }
                if (!"All".equals(action)) {
                    COSBase fieldsBase = dereference(params.getDictionaryObject(COSName.getPDFName("Fields")));
                    if (!(fieldsBase instanceof COSArray)) { malformed = true; continue; }
                    COSArray fields = (COSArray) fieldsBase;
                    for (int fieldIndex = 0; fieldIndex < fields.size(); fieldIndex++) {
                        COSBase field = dereference(fields.get(fieldIndex));
                        if (!(field instanceof com.tom_roush.pdfbox.cos.COSString)) {
                            malformed = true;
                            break;
                        }
                    }
                }
            }
        }
        if (malformed) {
            checks.add(new Check("DocMDP / FieldMDP transform references", State.FAIL,
                    "One or more transform references or permission values are malformed."));
        } else if (sawDocMdp || sawFieldMdp) {
            checks.add(new Check("DocMDP / FieldMDP transform references", State.REVIEW_REQUIRED,
                    "A DocMDP/FieldMDP policy is present. The app recognizes its presence but does not yet compare all field/object changes across revisions against that policy."));
        } else {
            checks.add(new Check("DocMDP / FieldMDP transform references", State.PASS,
                    "No DocMDP or FieldMDP transform was declared in this signature's /Reference array."));
        }
    }

    private static COSBase dereference(COSBase value) {
        COSBase current = value;
        while (current instanceof COSObject) current = ((COSObject) current).getObject();
        return current;
    }

    private static boolean isHex(byte value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static boolean whitespace(byte value) {
        return value == 0 || value == 9 || value == 10 || value == 12 || value == 13 || value == 32;
    }
}
