package com.nenotv.player;
import android.app.Activity;
import android.os.Build;
import android.view.*;
import android.graphics.Insets;

/** Keep browsing controls outside system bars, but let video use the whole display. */
public final class ScreenInsets {
    private ScreenInsets(){}
    public static void browsing(Activity a){
        ViewGroup content=a.findViewById(android.R.id.content);
        if(content==null||content.getChildCount()==0)return;
        View root=content.getChildAt(0);
        if(Build.VERSION.SDK_INT>=30){
            a.getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v,w)->{
                Insets i=w.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                v.setPadding(i.left,i.top,i.right,i.bottom);return w;
            });
            root.requestApplyInsets();
        }else root.setFitsSystemWindows(true);
    }
    public static void player(Activity a){
        if(Build.VERSION.SDK_INT>=30){
            a.getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c=a.getWindow().getInsetsController();
            if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.systemBars());}
        }else a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        View controls=a.findViewById(R.id.playerControls);
        if(controls!=null&&Build.VERSION.SDK_INT>=30){
            Object stored=controls.getTag(R.id.playerControls);if(!(stored instanceof int[])){stored=new int[]{controls.getPaddingLeft(),controls.getPaddingTop(),controls.getPaddingRight(),controls.getPaddingBottom()};controls.setTag(R.id.playerControls,stored);}final int[] padding=(int[])stored;final int l=padding[0],t=padding[1],r=padding[2],b=padding[3];
            controls.setOnApplyWindowInsetsListener((v,w)->{Insets i=w.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(l+i.left,t+i.top,r+i.right,b+i.bottom);return w;});
            controls.requestApplyInsets();
        }
    }
}
