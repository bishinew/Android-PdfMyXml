package com.hendrix.pdfmyxml;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Handler;

import com.hendrix.pdfmyxml.interfaces.IDisposable;
import com.hendrix.pdfmyxml.utils.BitmapUtils;
import com.hendrix.pdfmyxml.viewRenderer.AbstractViewRenderer;
import com.pdfjet.A4;
import com.pdfjet.Image;
import com.pdfjet.ImageType;
import com.pdfjet.PDF;
import com.pdfjet.Page;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * a PDF document creator
 *
 * @see com.hendrix.pdfmyxml.PdfDocument.Builder
 *
 * @author Tomer Shalev
 */
@SuppressWarnings("UnusedDeclaration")
public class PdfDocument implements IDisposable{
    private static final String sDefault_Filename_prefix                      = "pdf_";
    // android context
    private Context                             _ctx                    = null;
    // the orientation
    private A4_MODE                             _orientation            = A4_MODE.LANDSCAPE;
    // file name
    private String                              file_name;
    // file object of pdf
    private File                                file;
    // state of the rendering
    private boolean                             _isWorking              = false;
    // inflate on main thread
    private boolean                             _inflateOnMainThread    = false;
    // progress dialog
    private ProgressDialog                      _ringProgressDialog;
    // progress dialog message
    private String                              _txtProgressMessage     = "Generating Pdf..";
    // progress dialog title
    private String                              _txtProgressTitle       = "Please wait";
    // rendered pages streams
    protected ArrayList<InputStream>            _pages_rendered         = null;
    // views to render
    protected ArrayList<AbstractViewRenderer>   _pages                  = null;
    // async part
    private Handler                             _handler                = new Handler();
    // background thread
    private Thread                              _thread                 = null;
    // has IO error happened
    private boolean                             _error_io               = false;

    public PdfDocument(Context ctx) {
        setContext(ctx);

        _pages          = new ArrayList<>();
        _pages_rendered = new ArrayList<>();
    }

    /**
     * the document orientation, contains aspect ratio info
     */
    public enum A4_MODE {
        PORTRAIT(0.707f, A4.PORTRAIT), LANDSCAPE(1.41f, A4.LANDSCAPE);

        private float   _ar;
        private float[] _a4 = null;

        A4_MODE(float ar, float[] mode) {
            _ar = ar;
            _a4 = mode;
        }

        /**
         *
         * @return the aspect ratio of the mode {@code ar = width/height}
         */
        public float aspectRatio() { return _ar; }

        /**
         *
         * @return the corresponding mode in pdfjet lib
         */
        public float[] A4() {
            return _a4;
        }
    }

    /**
     * add a page with a custom class view renderer. please not that the bitmap of the view will be recycled.
     *
     * @param page a view renderer instance
     *
     * @see com.hendrix.pdfmyxml.viewRenderer.AbstractViewRenderer
     */
    public void addPage(AbstractViewRenderer page) {
        if(_inflateOnMainThread)
            renderView(page);
        else
            _pages.add(page);
    }

    /**
     * add a page with a rendered bitmap. the bitmap will not be recycled, it's up to
     * the user to recycle.
     *
     * @param page a bitmap
     *
     */
    public void addPage(Bitmap page) {
        ByteArrayInputStream stream = BitmapUtils.bitmapToPngInputStream(page);
        _pages_rendered.add(stream);
    }

    /**
     * set the context
     *
     * @param ctx the context
     */
    public void setContext(Context ctx) {
        _ctx = ctx;
    }

    /**
     *
     * @return get the orientation
     *
     * @see PdfDocument.A4_MODE
     */
    public A4_MODE getOrientation() {
        return _orientation;
    }

    /**
     * set the orientation
     *
     * @param orientation {@code {PORTRAIT, LANDSCAPE}}
     *
     * @see PdfDocument.A4_MODE
     */
    public void setOrientation(A4_MODE orientation) {
        _orientation = orientation;
    }

    /**
     * set the text message for the progress dialog
     *
     * @param resId a string resource identifier
     */
    public void setProgressMessage(int resId) {
        _txtProgressMessage = _ctx.getString(resId);
    }

    /**
     * set the text title for the progress dialog
     *
     * @param resId a string resource identifier
     */
    public void setProgressTitle(int resId) {
        _txtProgressTitle = _ctx.getString(resId);
    }

    /**
     *
     * @return the pdf file name
     */
    public String getFileName() {
        return file_name;
    }

    /**
     * set the file name
     *
     * @param fileName the pdf file name
     */
    public void setFileName(String fileName) {
        file_name = fileName;
    }

    /**
     *
     * @return the pdf {@link java.io.File} if available
     */
    public File getFile() {
        return file;
    }

    /**
     * does the a PDF is generating now?
     *
     * @return {@code true/false}
     */
    public boolean isWorking() {
        return _isWorking;
    }

    /**
     * set the inflation of views on the Main thread.
     * use it, in case you are having inflation errors.
     * by default, and even though not recommended by Google, the
     * inflation and rendering to bitmaps happens on the background thread.
     *
     * @param enabled {@code true/false}
     */
    public void setInflateOnMainThread(boolean enabled) {
        _inflateOnMainThread = enabled;
    }

    /**
     * create the pdf and render according to report types and a time frame
     *
     * @param window an {@link android.app.Activity} in which to display a progress dialog (Optional)
     */
    public void createPdf(Activity window) {
        if (isWorking())
            return;

        Resources res = _ctx.getResources();

        if (window != null) {
            if(_ringProgressDialog !=null) {
                _ringProgressDialog.dismiss();
            }

            _ringProgressDialog = ProgressDialog.show(window, _txtProgressTitle, _txtProgressMessage, true);

            if(!_ringProgressDialog.isShowing())
                _ringProgressDialog.show();
        }

        _thread = new Thread(new Runnable() {
            @Override
            public void run() {
                _isWorking = true;

                _pages_rendered.clear();

                if(!_inflateOnMainThread) {
                    for (AbstractViewRenderer view : _pages) {
                        renderView(view);
                    }
                }

                internal_generatePdf();

                // go back to the main thread
                _handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if(_ringProgressDialog !=null)
                            _ringProgressDialog.dismiss();

                        if(_listener != null) {
                            if(_error_io)
                                _listener.onError();
                            else
                                _listener.onComplete(file);
                        }

                        release();

                    }
                });

            }

        });

        _thread.start();

    }

    private Callback _listener = null;

    /**
     * set a listener for the PDF generation events
     *
     * @param listener a {@link com.hendrix.pdfmyxml.PdfDocument.Callback}
     */
    public void setListener(Callback listener) {
        _listener = listener;
    }

    private void internal_generatePdf()
    {
        String name                 = (file_name == null) ? sDefault_Filename_prefix + System.currentTimeMillis() : file_name;

        file_name                   = name + ".pdf";
        //File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "pdf");
        File dir                    = new File(_ctx.getExternalFilesDir(null) + File.separator + "pdfdroid");

        boolean make                = dir.mkdir();

        file                        = new File(dir, file_name);
        _error_io                   = false;

        try {
            FileOutputStream fos    = new FileOutputStream(file);//"Example_01.pdf");
            PDF pdf                 = new com.pdfjet.PDF(fos);

            Page page;
            Image image;
            float ar;

            for (InputStream inputStream : _pages_rendered) {
                page                = new Page(pdf, _orientation.A4());
                image               = new Image(pdf, inputStream, ImageType.PNG);

                inputStream.close(); //doesn't do anything in byte array

                ar                  = page.getWidth() / image.getWidth();

                image.scaleBy(ar);

                image.drawOn(page);
            }

            pdf.flush();
            fos.close();
        }
        catch (Exception exc) {
            exc.printStackTrace();
            _error_io               = true;
        }

    }

    /**
     * render the view
     *
     * @param page {@link com.hendrix.pdfmyxml.viewRenderer.AbstractViewRenderer} instance
     */
    private void renderView(AbstractViewRenderer page) {
        page.attachContext(_ctx);

        Bitmap bmp                  = page.render(0, 0);
        ByteArrayInputStream stream = BitmapUtils.bitmapToPngInputStream(bmp);

        page.disposeBitmap();

        _pages_rendered.add(stream);
    }

    /**
     * release this class for future usage
     */
    private void release() {
        _pages.clear();
        _pages_rendered.clear();

        _error_io   =   false;
        _isWorking  = false;

        file_name   = null;
        file        = null;

        if(_ringProgressDialog != null) {
            _ringProgressDialog.dismiss();
            _ringProgressDialog = null;
        }
    }

    /**
     * dispose the item
     */
    @Override
    public void dispose() {
        release();

        _listener = null;
        _handler = null;
        _thread = null;
        _ringProgressDialog = null;
    }

    /**
     * callback interface for PDF creation
     */
    public interface Callback {

        /**
         * successful completion of pdf
         *
         * @param file the file
         */
        void onComplete(File file);

        /**
         * error creating the PDF
         */
        void onError();
    }

    /**
     * a mutable builder for document
     */
    public static class Builder {
        private PdfDocument _doc = null;

        public Builder() {
            _doc = new PdfDocument(null);
        }

        /**
         * set the context
         *
         * @param ctx the context
         */
        public Builder context(Context ctx) {
            _doc.setContext(ctx);

            return this;
        }

        /**
         * create the pdf document instance. afterwards, use {@link com.hendrix.pdfmyxml.PdfDocument#createPdf(android.app.Activity)}
         *
         * @return a {@link com.hendrix.pdfmyxml.PdfDocument}
         */
        public PdfDocument create() {
            return _doc;
        }

        /**
         * add a page with a custom class view renderer. please not that the bitmap of the view will be recycled.
         *
         * @param page a view renderer instance
         *
         * @see com.hendrix.pdfmyxml.viewRenderer.AbstractViewRenderer
         */
        public Builder addPage(AbstractViewRenderer page) {
            _doc.addPage(page);

            return this;
        }

        /**
         * add a page with a rendered bitmap. the bitmap will not be recycled, it's up to
         * the user to recycle.
         *
         * @param page a bitmap
         *
         */
        public Builder addPage(Bitmap page) {
            _doc.addPage(page);

            return this;
        }

        /**
         * set the file name
         *
         * @param name the pdf file name
         */
        public Builder filename(String name) {
            _doc.setFileName(name);

            return this;
        }

        /**
         * set the inflation of views on the Main thread.
         * use it, in case you are having inflation errors.
         * by default, and even though not recommended by Google, the
         * inflation and rendering to bitmaps happens on the background thread.
         *
         * @param enabled {@code true/false}
         */
        public Builder inflateOnMainThread(boolean enabled) {
            _doc.setInflateOnMainThread(enabled);

            return this;
        }

        /**
         * set a listener for the PDF generation events
         *
         * @param listener a {@link com.hendrix.pdfmyxml.PdfDocument.Callback}
         */
        public Builder listener(Callback listener) {
            _doc.setListener(listener);

            return this;
        }

        /**
         * set the orientation
         *
         * @param mode {@code {PORTRAIT, LANDSCAPE}}
         *
         * @see PdfDocument.A4_MODE
         */
        public Builder orientation(A4_MODE mode) {
            _doc.setOrientation(mode);

            return this;
        }

        /**
         * set the text message for the progress dialog
         *
         * @param resId a string resource identifier
         */
        public Builder progressMessage(int resId) {
            _doc.setProgressMessage(resId);

            return this;
        }

        /**
         * set the text title for the progress dialog
         *
         * @param resId a string resource identifier
         */
        public Builder progressTitle(int resId) {
            _doc.setProgressTitle(resId);

            return this;
        }

    }

}