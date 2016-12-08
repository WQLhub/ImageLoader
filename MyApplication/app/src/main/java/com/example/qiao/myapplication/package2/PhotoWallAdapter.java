package com.example.qiao.myapplication.package2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.LruCache;
import android.widget.ArrayAdapter;
import android.widget.GridView;

import com.example.qiao.myapplication.package1.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Created by lenovo on 2016/12/8.
 */

public class PhotoWallAdapter extends ArrayAdapter {

//    private Set<> taskCollection;

    private LruCache<String,Bitmap> mMemoeryCache;

    private DiskLruCache mDiskLruCache;

    private GridView mPhotoWall;

    public PhotoWallAdapter(Context context, int resource) {
        super(context, resource);
    }

    public PhotoWallAdapter(Context context, int resource, int textViewResourceId) {
        super(context, resource, textViewResourceId);
    }

    public PhotoWallAdapter(Context context, int resource, Object[] objects) {
        super(context, resource, objects);
    }

    public PhotoWallAdapter(Context context,int resId,String[] objects,GridView mPhotoWall){
        super(context, resId, objects);

        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheSize = maxMemory/8;
        mMemoeryCache = new LruCache<String,Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        try {
            File cacheDir = getDiskDir(context,"bitmap");
            if (!cacheDir.exists()){
                cacheDir.mkdirs();
            }

            mDiskLruCache = mDiskLruCache.open(cacheDir,1,1,10*1024*1024);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public File getDiskDir(Context context,String uniqueName){
        String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())||!Environment.isExternalStorageRemovable()){
            cachePath = context.getExternalCacheDir().getPath();
        }else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath+File.separator+uniqueName);
    }

    public void addBitmapToMemory(String key,Bitmap bitmap){
        if (getBitmapFromMemory(key)==null){
            mMemoeryCache.put(key,bitmap);
        }
    }

    public Bitmap getBitmapFromMemory(String url){
        return mMemoeryCache.get(url);
    }

    public String hashKeyForDisk(String key){
        String cacheKey;
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(key.getBytes());
            cacheKey = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
            e.printStackTrace();
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    class BitmapWorkerTask extends AsyncTask<String,Void,Bitmap>{

        private String imageUrl;

        @Override
        protected Bitmap doInBackground(String... params) {

            imageUrl = params[0];
            FileDescriptor fileDescriptor = null;
            FileInputStream fileInputStream = null;
            DiskLruCache.Snapshot snapshot = null;

            try {
                String key = hashKeyForDisk(imageUrl);
                snapshot = mDiskLruCache.get(key);
                if (snapshot==null){
                    DiskLruCache.Editor editor = mDiskLruCache.edit(key);
                    if (editor!=null){
                        OutputStream outputStream = editor.newOutputStream(0);
                        if (downloadUlrToStream(imageUrl,outputStream)){
                            editor.commit();
                        }else {
                            editor.abort();
                        }
                        mDiskLruCache.flush();
                    }
                }
                if (snapshot!=null){
                    fileInputStream = (FileInputStream) snapshot.getInputStream(0);
                    fileDescriptor = fileInputStream.getFD();
                }
                Bitmap bitmap = null;
                if (fileDescriptor!=null){
                    bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
                }
                if (bitmap!=null){
                    addBitmapToMemory(params[0],bitmap);
                }
                return bitmap;
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                if (fileDescriptor!=null&&fileInputStream!=null){
                    try {
                        fileInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            return null;
        }


        private boolean downloadUlrToStream(String urlString,OutputStream outputStream){
            HttpURLConnection urlConnection = null;
            BufferedOutputStream out = null;
            BufferedInputStream in = null;

            try {
                URL url = new URL(urlString);
                urlConnection = (HttpURLConnection) url.openConnection();
                in = new BufferedInputStream(urlConnection.getInputStream(),8*1024);
                out = new BufferedOutputStream(outputStream,8*1024);
                int b;
                while ((b=in.read())!=-1){
                    out.write(b);
                }
                return true;
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                if (out!=null){
                    try {
                        out.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (in!=null){
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            return false;
        }

    }




}
