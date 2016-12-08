package com.example.qiao.myapplication.package1;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileDescriptor;

/**
 * Created by qiao on 2016/12/6.
 */

public class ImageResizer {

    private static final String TAG = "ImageResizer";

    public ImageResizer(){}

    public Bitmap decodeSimpleBitmapFromResource(Resources res,int resid,int reqWidth,int reqHeight){

        //first decode with inJustDecodeBounds=true to check dimensions
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res,resid,options);

        //calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options,reqWidth,reqHeight);

        //Decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeResource(res,resid,options);
    }

    public Bitmap decodeSampledBitmapFromFileDecriptor(FileDescriptor fd,int reqWidth,int reqHeight){
        //First decode with inJustDecodeBounds=true to check dimensions
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd,null,options);

        //calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options,reqWidth,reqHeight);

        //decode bitmap with inSampleSize set
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFileDescriptor(fd,null,options);

    }

    private int calculateInSampleSize(BitmapFactory.Options options,int reqWidth,int reqHeight){

        //Raw height and width of image
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height>reqHeight||width>reqWidth){
            int halfHeight = height/2;
            int halfWidth = width/2;

            //calcualte the largest inSampleSize value that is a power of 2 and keeps both
            //height and width lager than the requested height and width
            while (halfHeight/inSampleSize>=reqHeight&&halfWidth/inSampleSize>=reqWidth){
                inSampleSize*= 2;
            }
        }
        return inSampleSize;
    }

}
