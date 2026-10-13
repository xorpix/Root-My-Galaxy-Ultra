package dev.experimental.rmgu.bootprobe;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Exposes only the embedded, verified helper APK through a temporary read grant. */
public final class HelperApkProvider extends ContentProvider {
    static final String AUTHORITY="dev.experimental.rmgu.bootprobe.helper-apk";
    @Override public boolean onCreate(){return true;}
    static File staged(Context context){return new File(context.createDeviceProtectedStorageContext().getFilesDir(),"privileged-helper.apk");}
    private File file(Uri uri)throws FileNotFoundException {
        if(!AUTHORITY.equals(uri.getAuthority())||!"/helper.apk".equals(uri.getPath()))throw new FileNotFoundException();
        File result=staged(getContext());if(!result.isFile())throw new FileNotFoundException();return result;
    }
    @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException();return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){
        try{File f=file(u);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{"RMGU-Privileged-Boot-Helper-0.3.0.apk",f.length()});return c;}catch(FileNotFoundException error){return null;}
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
