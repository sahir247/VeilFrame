package com.veilframe.app.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.pdf.PrintedPdfDocument;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android PrintDocumentAdapter replicating Adobe Acrobat's print rendering pipeline.
 *
 * In Adobe Acrobat DC:
 * - When printing a digitally signed document (such as e-Aadhaar), Acrobat does NOT modify
 *   the PDF file on disk.
 * - Acrobat spools the rendered pages to the operating system print subsystem with the
 *   verified signature green checkmark dynamically overlaid on the print canvas in RAM.
 * - This adapter achieves 100% parity with Adobe's architecture: physical prints and 'Save as PDF'
 *   spool exports feature the verified checkmark, while preserving 100% cryptographic
 *   integrity of the original signed PDF on disk.
 */
public final class PdfPrintDocumentAdapter extends PrintDocumentAdapter {

    private final Context context;
    private final byte[] pdfBytes;
    private File pdfFile;
    private final String password;
    private final String documentName;
    private final boolean isVerified;
    private final String signerName;
    private final String signingDate;

    private PrintedPdfDocument printedPdfDocument;
    private int totalPages = 0;
    // Android invokes onLayout/onWrite on Main. PDFBox parsing and page rasterization
    // must run on a private serialized worker, not block UI input or animations.
    private final ExecutorService printWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VeilFrame-PDF-Print");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public PdfPrintDocumentAdapter(Context context, byte[] pdfBytes, String password, String documentName,
                                   boolean isVerified, String signerName, String signingDate) {
        this.context = context;
        DynamicSignatureOverlay.init(context);
        this.pdfBytes = pdfBytes;
        this.password = password;
        this.documentName = documentName;
        this.isVerified = isVerified;
        this.signerName = signerName;
        this.signingDate = signingDate;
    }

    /**
     * File-backed print source for decrypted PDFs. Retains the legacy byte-array
     * constructor for signature and other existing callers.
     */
    public PdfPrintDocumentAdapter(Context context, File pdfFile, String password, String documentName,
                                   boolean isVerified, String signerName, String signingDate) {
        this(context, (byte[]) null, password, documentName, isVerified, signerName, signingDate);
        if (pdfFile == null) throw new IllegalArgumentException("pdfFile cannot be null");
        this.pdfFile = pdfFile;
    }

    private InputStream openPdfInput() throws IOException {
        if (pdfFile != null) return new FileInputStream(pdfFile);
        if (pdfBytes == null) throw new IOException("PDF print input is missing");
        return new ByteArrayInputStream(pdfBytes);
    }

    private PDDocument loadPdf(InputStream input) throws IOException {
        return password != null && !password.isEmpty()
                ? PDDocument.load(input, password, MemoryUsageSetting.setupTempFileOnly())
                : PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly());
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                         CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
        if (cancellationSignal.isCanceled() || finished.get()) {
            callback.onLayoutCancelled();
            return;
        }

        try {
            printWorker.execute(() -> {
                if (cancellationSignal.isCanceled() || finished.get()) {
                    callback.onLayoutCancelled();
                    return;
                }
                try (InputStream input = openPdfInput(); PDDocument doc = loadPdf(input)) {
                    final int pages = doc.getNumberOfPages();
                    if (cancellationSignal.isCanceled() || finished.get()) {
                        callback.onLayoutCancelled();
                        return;
                    }

                    if (printedPdfDocument != null) printedPdfDocument.close();
                    printedPdfDocument = new PrintedPdfDocument(context, newAttributes);
                    totalPages = pages;
                    PrintDocumentInfo info = new PrintDocumentInfo.Builder(documentName)
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(pages)
                            .build();
                    callback.onLayoutFinished(info, !newAttributes.equals(oldAttributes));
                } catch (Exception error) {
                    if (cancellationSignal.isCanceled() || finished.get()) {
                        callback.onLayoutCancelled();
                    } else {
                        callback.onLayoutFailed("Failed to read document for printing: " + error.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            callback.onLayoutCancelled();
        }
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal cancellationSignal, WriteResultCallback callback) {
        if (cancellationSignal.isCanceled() || finished.get()) {
            callback.onWriteCancelled();
            return;
        }

        try {
            printWorker.execute(() -> {
                if (cancellationSignal.isCanceled() || finished.get()) {
                    callback.onWriteCancelled();
                    return;
                }
                try (InputStream input = openPdfInput(); PDDocument doc = loadPdf(input)) {
                    if (printedPdfDocument == null) {
                        callback.onWriteFailed("Print layout was not initialized");
                        return;
                    }

                    PDFRenderer renderer = new PDFRenderer(doc);
                    for (int i = 0; i < totalPages; i++) {
                        if (!containsPage(pages, i)) continue;
                        if (cancellationSignal.isCanceled() || finished.get() ||
                                Thread.currentThread().isInterrupted()) {
                            callback.onWriteCancelled();
                            return;
                        }

                        // Render before creating an Android print page: PDFBox may fail on
                        // malformed content, and leaving an open PdfDocument.Page makes
                        // PrintedPdfDocument.close() throw on the worker thread.
                        Bitmap pageBmp = renderer.renderImage(i, 2.0f);
                        try {
                            PdfDocument.Page page = printedPdfDocument.startPage(i);
                            try {
                                Canvas canvas = page.getCanvas();
                                Rect src = new Rect(0, 0, pageBmp.getWidth(), pageBmp.getHeight());
                                Rect dst = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
                                canvas.drawBitmap(pageBmp, src, dst, null);
                                if (isVerified) {
                                    overlaySignatureWidgetsForPage(doc, i, canvas,
                                            canvas.getWidth(), canvas.getHeight());
                                }
                            } finally {
                                // Finish even if drawing the dynamic signature badge fails.
                                printedPdfDocument.finishPage(page);
                            }
                        } finally {
                            pageBmp.recycle();
                        }
                    }

                    if (cancellationSignal.isCanceled() || finished.get()) {
                        callback.onWriteCancelled();
                        return;
                    }
                    try (FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                        printedPdfDocument.writeTo(out);
                    }
                    if (cancellationSignal.isCanceled() || finished.get()) {
                        callback.onWriteCancelled();
                    } else {
                        callback.onWriteFinished(pages != null ? pages : new PageRange[]{PageRange.ALL_PAGES});
                    }
                } catch (Exception error) {
                    if (cancellationSignal.isCanceled() || finished.get()) {
                        callback.onWriteCancelled();
                    } else {
                        callback.onWriteFailed("Print write failed: " + error.getMessage());
                    }
                } finally {
                    if (printedPdfDocument != null) {
                        printedPdfDocument.close();
                        printedPdfDocument = null;
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            callback.onWriteCancelled();
        }
    }

    @Override
    public void onFinish() {
        finished.set(true);
        try {
            // Serialize document cleanup behind any in-flight layout/write.
            printWorker.execute(() -> {
                if (printedPdfDocument != null) {
                    printedPdfDocument.close();
                    printedPdfDocument = null;
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Already terminated.
        } finally {
            printWorker.shutdown();
        }
        super.onFinish();
    }

    private void overlaySignatureWidgetsForPage(PDDocument doc, int pageIndex, Canvas canvas, int canvasWidth, int canvasHeight) {
        PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
        if (acroForm == null) return;

        PDPage page = doc.getPage(pageIndex);
        PDRectangle crop = page.getCropBox();
        float cropW = crop.getWidth();
        float cropH = crop.getHeight();

        for (PDField field : acroForm.getFields()) {
            if (!(field instanceof PDSignatureField)) continue;
            for (PDAnnotationWidget widget : field.getWidgets()) {
                boolean isOnThisPage = false;
                if (widget.getPage() != null) {
                    isOnThisPage = doc.getPages().indexOf(widget.getPage()) == pageIndex;
                } else {
                    try {
                        List<PDAnnotation> pageAnnots = page.getAnnotations();
                        if (pageAnnots != null) {
                            for (PDAnnotation annot : pageAnnots) {
                                if (annot.getCOSObject().equals(widget.getCOSObject())) {
                                    isOnThisPage = true;
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) { }
                }

                if (isOnThisPage) {
                    PDRectangle rect = widget.getRectangle();
                    if (rect != null && rect.getWidth() > 0 && rect.getHeight() > 0) {
                        float scaleX = (float) canvasWidth / cropW;
                        float scaleY = (float) canvasHeight / cropH;
                        float left = (rect.getLowerLeftX() - crop.getLowerLeftX()) * scaleX;
                        float right = (rect.getUpperRightX() - crop.getLowerLeftX()) * scaleX;
                        float top = (crop.getUpperRightY() - rect.getUpperRightY()) * scaleY;
                        float bottom = (crop.getUpperRightY() - rect.getLowerLeftY()) * scaleY;

                        RectF badgeRect = new RectF(left, top, right, bottom);
                        DynamicSignatureOverlay.AppearanceLayout layout = DynamicSignatureOverlay.extractAppearanceLayout(widget);
                        DynamicSignatureOverlay.drawSignatureBadge(canvas, badgeRect, signerName, signingDate, layout, true);
                    }
                }
            }
        }
    }

    private boolean containsPage(PageRange[] pages, int page) {
        if (pages == null) return true;
        for (PageRange range : pages) {
            if (page >= range.getStart() && page <= range.getEnd()) {
                return true;
            }
        }
        return false;
    }
}
