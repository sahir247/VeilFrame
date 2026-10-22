package com.veilframe.app.pdf;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class PdfStamperEngineParityTest {

    @Test
    public void testDynamicCheckmarkIncomeBannerParity() throws Exception {
        PDDocument doc = new PDDocument();
        Method method = PdfStamperEngine.class.getDeclaredMethod(
                "createAdobeGreenCheckmarkForm", PDDocument.class, float.class, float.class, float.class, float.class);
        method.setAccessible(true);

        // Standard Income certificate banner: 120 x 60 pt
        PDFormXObject form = (PDFormXObject) method.invoke(null, doc, 100f, 100f, 120f, 60f);
        Assert.assertNotNull("Form XObject must be generated", form);

        InputStream in = form.getCOSObject().createInputStream();
        byte[] bytes = PdfStamperEngine.readStreamBytes(in);
        String stream = new String(bytes, StandardCharsets.ISO_8859_1);

        // Rule 3: Multi-layer structure (Shadow + Green Fill + Subtle Outline)
        Assert.assertTrue("Must include black drop shadow (0 g 406 0 m ... h f)", stream.contains("0 g\n406 0 m") && stream.contains("h f"));
        Assert.assertTrue("Must include vibrant Adobe green fill (0 0.7 0.23 rg 377 50 m ... h f)", stream.contains("0 0.7 0.23 rg\n377 50 m") && stream.contains("h f"));
        Assert.assertTrue("Must include subtle outer outline stroke (0 G 15 w ... h S)", stream.contains("0 G\n15 w") && stream.contains("h S"));
        Assert.assertFalse("Must NOT include extraneous white underlay (1 1 1 rg)", stream.contains("1 1 1 rg"));

        // Rule 2: Ground truth vertices from Adobe Acrobat DC DigSig.api (% DSDoubleValid)
        Assert.assertTrue("Stream must contain canonical right tip (787 967 l)", stream.contains("787 967 l"));
        Assert.assertTrue("Stream must contain canonical apex (377 50 m)", stream.contains("377 50 m"));

        doc.close();
    }

    @Test
    public void testDynamicCheckmarkCompactAadhaarParity() throws Exception {
        PDDocument doc = new PDDocument();
        Method method = PdfStamperEngine.class.getDeclaredMethod(
                "createAdobeGreenCheckmarkForm", PDDocument.class, float.class, float.class, float.class, float.class);
        method.setAccessible(true);

        // Compact Aadhaar stamp: 50 x 30 pt
        PDFormXObject form = (PDFormXObject) method.invoke(null, doc, 100f, 100f, 50f, 30f);
        Assert.assertNotNull("Form XObject must be generated", form);

        InputStream in = form.getCOSObject().createInputStream();
        byte[] bytes = PdfStamperEngine.readStreamBytes(in);
        String stream = new String(bytes, StandardCharsets.ISO_8859_1);

        // Multi-layer structure
        Assert.assertTrue("Compact checkmark must have shadow (0 g)", stream.contains("0 g\n406 0 m"));
        Assert.assertTrue("Compact checkmark must have green fill (0 0.7 0.23 rg)", stream.contains("0 0.7 0.23 rg\n377 50 m"));
        Assert.assertTrue("Compact checkmark must have subtle outline (0 G 15 w)", stream.contains("0 G\n15 w") && stream.contains("h S"));
        Assert.assertFalse("Compact checkmark must NOT have white underlay", stream.contains("1 1 1 rg"));

        doc.close();
    }

    @Test
    public void testRealDocumentRectification() throws Exception {
        File scratchDir = new File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/scratch");
        File incomeFile = new File(scratchDir, "income.pdf");
        if (incomeFile.exists()) {
            byte[] inBytes = Files.readAllBytes(incomeFile.toPath());
            PdfStamperEngine.StampResult res = PdfStamperEngine.processPdf(inBytes, null);
            Assert.assertNotNull("Result must not be null", res);
            Assert.assertTrue("Must have rectified PDF bytes", res.rectifiedPdf != null && res.rectifiedPdf.length > 0);
            File outFile = new File(scratchDir, "production_engine_income.pdf");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(res.rectifiedPdf);
            }
            Assert.assertTrue("Output file must exist", outFile.exists());
        }

        File aadhaarFile = new File(scratchDir, "eaadhaar.pdf");
        if (aadhaarFile.exists()) {
            byte[] inBytes = Files.readAllBytes(aadhaarFile.toPath());
            PdfStamperEngine.StampResult res = PdfStamperEngine.processPdf(inBytes, "KHON2004");
            Assert.assertNotNull("Result must not be null", res);
            Assert.assertTrue("Must have rectified PDF bytes", res.rectifiedPdf != null && res.rectifiedPdf.length > 0);
            File outFile = new File(scratchDir, "production_engine_eaadhaar.pdf");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(res.rectifiedPdf);
            }
            Assert.assertTrue("Output file must exist", outFile.exists());
        }
        File rationFile = new File(scratchDir, "ration.pdf");
        if (rationFile.exists()) {
            byte[] inBytes = Files.readAllBytes(rationFile.toPath());
            PdfStamperEngine.StampResult res = PdfStamperEngine.processPdf(inBytes, null);
            Assert.assertNotNull("Result must not be null", res);
            Assert.assertTrue("Must have rectified PDF bytes", res.rectifiedPdf != null && res.rectifiedPdf.length > 0);
            File outFile = new File(scratchDir, "production_engine_ration.pdf");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(res.rectifiedPdf);
            }
            Assert.assertTrue("Output file must exist", outFile.exists());
        }
    }
}
