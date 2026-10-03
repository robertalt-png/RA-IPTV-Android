package com.nenotv.admin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;

public final class TrendView extends View {
    private List<DashboardData.DayPoint> points = Collections.emptyList();
    private final Paint grid = new Paint(1);
    private final Paint line = new Paint(1);
    private final Paint dots = new Paint(1);
    private final Paint text = new Paint(1);

    public TrendView(Context c) { super(c); init(); }
    public TrendView(Context c, AttributeSet a) { super(c,a); init(); }

    private void init() {
        grid.setColor(0x332E5C85); grid.setStrokeWidth(1f);
        line.setColor(0xFF11DDE8); line.setStrokeWidth(5f); line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeJoin(Paint.Join.ROUND);
        dots.setColor(0xFF1F75FF);
        text.setColor(0xFF91A3BB); text.setTextSize(26f);
        setMinimumHeight(220);
    }

    public void setPoints(List<DashboardData.DayPoint> value) {
        points = value == null ? Collections.emptyList() : value;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w=getWidth(), h=getHeight(), left=20f, right=w-20f, top=22f, bottom=h-42f;
        for(int i=0;i<4;i++){float y=top+(bottom-top)*i/3f;c.drawLine(left,y,right,y,grid);}
        if(points.isEmpty()){
            text.setTextAlign(Paint.Align.CENTER);c.drawText("Nog geen gegevens",w/2,h/2,text);return;
        }
        int max=1;for(DashboardData.DayPoint p:points)max=Math.max(max,p.pageviews);
        Path path=new Path();
        for(int i=0;i<points.size();i++){
            float x=points.size()==1?w/2:left+(right-left)*i/(points.size()-1f);
            float y=bottom-(bottom-top)*(points.get(i).pageviews/(float)max);
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        c.drawPath(path,line);
        for(int i=0;i<points.size();i++){
            float x=points.size()==1?w/2:left+(right-left)*i/(points.size()-1f);
            float y=bottom-(bottom-top)*(points.get(i).pageviews/(float)max);
            c.drawCircle(x,y,7f,dots);
        }
        text.setTextAlign(Paint.Align.LEFT); c.drawText("0",left,bottom+30f,text);
        text.setTextAlign(Paint.Align.RIGHT); c.drawText(String.valueOf(max),right,top+10f,text);
    }
}
