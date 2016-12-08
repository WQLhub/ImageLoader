package com.example.qiao.myapplication.package1;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;


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
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by qiao on 2016/12/6.
 */

public class ImageLoader {

    /*
    *
    * 实现一个ImageLoader的大致思路：
    *
    * 1、实例化LruCache
    *   (1)获取cacheSize，实现sizeOf方法;
    *   (2)LruCache中数据的存取 很简单
    * 2、实例化DiskLruCache（获取文件路径，DiskLruCache实例化）
    *   （1）存取文件都涉及到对url的MD5加密，加密后的字符串作为key来存储内容
    *   （2）存储的时候，需要从网络获取流，涉及到urlConnection获取inputStream，DiskLruCache获取outputStream，从in中读取，写入到out中，
    *   实现从网络到本地Disk的转存的过程
    *   （3）读取的时候，需要对图片进行压缩处理（本质所有读取都是从这里的，即使从网络下载也要先写入本地，再从本地读取）
    * 3、实现最终的图片读取方法，其中涉及到三级缓存的分级读取；实现图片存储方法，涉及到三层存储；
    * 4、实例化线程池
    *   （1）实例化线程池的接口,ThreadFactory
    *   （2）实例化Excutor对象
    *   （3）实例化图片加载的Task
    * */


    private static final String TAG = "ImageLoader";
    public static final int MESSAGE_POST_RESULT = 1;
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT+1;
    private static final int MAX_POOL_SIZE = CPU_COUNT*2+1;
    private static final long KEEP_ALIVE = 10L;

    private static final int TAG_KEY_URI = android.support.v7.appcompat.R.id.image;
    private static final long DISK_CACHE_SIZE = 1024*1024*50;
    private static final int IO_BUFFER_SIZE = 8*1024;
    private static final int DISK_CACHE_INDEX = 0;
    private boolean mIsDiskLruCacheCreated = false;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r,"ImageLoader#"+mCount.getAndIncrement());
        }
    };

    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE,
            MAX_POOL_SIZE,KEEP_ALIVE,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(),sThreadFactory);

    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
//            imageView.setImageBitmap(result.bitmap);
            String uri = (String) imageView.getTag();
            if (uri.equals(result.url)){
                imageView.setImageBitmap(result.bitmap);
            }else {
                Log.w(TAG,"set image bitmap,but url has changed,ingored;");
            }
        }
    };

    private Context mContext;
    private ImageResizer mImageResize = new ImageResizer();
    private LruCache<String,Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;

    private ImageLoader(Context context){
        mContext = context.getApplicationContext();
        //实例化LruCache
        int maxMemory = (int) (Runtime.getRuntime().maxMemory()/1024);
        int cacheSize = maxMemory/8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes()*value.getHeight()/1024;
            }
        };

        //实例化DiskLruCache
        File diskCacheDir = FileUtil.getDiskCacheDir(mContext,"bitmap");
        if (!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }
        if (FileUtil.getUsableSpace(diskCacheDir)>DISK_CACHE_SIZE){
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    private void addBitmapToMemoryCache(String key,Bitmap bitmap){
        if (getBitmapFromMemoryCache(key)==null){
            mMemoryCache.put(key,bitmap);
        }
    }

    private Bitmap getBitmapFromMemoryCache(String key){
        return mMemoryCache.get(key);
    }

    public void bindBitmap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight){
        imageView.setTag(TAG_KEY_URI,uri);
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap!=null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBimtapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri,reqWidth,reqHeight);
                if (bitmap!=null){
                    LoaderResult result = new LoaderResult(imageView,uri,bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT,result).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBimtapTask);
    }

    public Bitmap loadBitmap(String uri,int reqWidth,int reqHeight){
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap!=null){
            return bitmap;
        }
        try {
            bitmap = loadBitmapFromDiskCache(uri,reqWidth,reqHeight);
            if (bitmap!=null){
                Log.d(TAG,"loadBitmapFroDisk,url:"+uri);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri,reqWidth,reqHeight);
            Log.d(TAG,"loadBitmapFromHttp,url:"+uri);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (bitmap==null&&!mIsDiskLruCacheCreated){
            Log.w(TAG,"encuner error,DiskLruCache is not created!");
            bitmap = downloadBitmapFromUri(uri);
        }
        return bitmap;
    }

    private Bitmap loadBitmapFromDiskCache(String url,int reqWidth,int reqHeight)throws Exception{
        if (Looper.myLooper()==Looper.getMainLooper()){
            Log.w(TAG,"load bitmap from UI Thread,it is not recommend!");
        }
        if (mDiskLruCache==null){
            return null;
        }

        Bitmap bitmap = null;
        String key = FileUtil.hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot!=null){
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResize.decodeSampledBitmapFromFileDecriptor(fileDescriptor,reqWidth,reqHeight);
            if (bitmap!=null){
                addBitmapToMemoryCache(key,bitmap);
            }
        }
        return bitmap;
    }

    public Bitmap loadBitmapFromMemCache(String url){
        String key = FileUtil.hashKeyFromUrl(url);
        return getBitmapFromMemoryCache(key);
    }

    //从Http下载流，然后缓存到本地，再从本地取出还原成图片返回
    private Bitmap loadBitmapFromHttp(String url,int reqWidth,int reqHeight) throws Exception{
        if (Looper.myLooper()==Looper.getMainLooper()){
            throw new RuntimeException("can not visit network from UI Thread");
        }
        if (mDiskLruCache==null){
            return null;
        }
        addBitmapToDisk(url);
        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    //其中掺杂网络操作   因为只有网络下载下来的图片才有加载到本地磁盘的必要
    private void addBitmapToDisk(String url)throws Exception{
        String key = FileUtil.hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor!=null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUriToStream(url,outputStream)){
                editor.commit();
            }else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
    }

    //直接从网络上获取到Bitmap
    private Bitmap downloadBitmapFromUri(String urlString){
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if (urlConnection!=null){
                urlConnection.disconnect();
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return bitmap;
    }

    //从网络上获取到图片流，写入到DiskLruCache的输出流中
    public boolean downloadUriToStream(String urlString,OutputStream outputStream){
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(),IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream,IO_BUFFER_SIZE);

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
            if (urlConnection!=null){
                urlConnection.disconnect();
            }
            try {
                in.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private static class LoaderResult{
        public ImageView imageView;
        public String url;
        public Bitmap bitmap;
        public LoaderResult(ImageView imageView,String url,Bitmap bitmap){
            this.imageView = imageView;
            this.url = url;
            this.bitmap = bitmap;
        }
    }
}
