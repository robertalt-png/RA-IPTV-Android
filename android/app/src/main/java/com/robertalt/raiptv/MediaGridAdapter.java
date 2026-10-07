package com.nenotv.player;

import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.nenotv.player.model.*;
import com.nenotv.player.storage.*;
import com.nenotv.player.provider.Provider;
import java.util.*;
import java.util.concurrent.*;

/** Lightweight recycled grid for very large IPTV libraries. Only visible cards own Views/images. */
public class MediaGridAdapter extends BaseAdapter {
    private final Context context;
    private final LibraryStore store;
    private List<MediaEntry> items=new ArrayList<>();
    private boolean liveMode=false;
    private volatile EpgRequests epgRequests;
    private final EpgRequests.Cache epgCache=new EpgRequests.Cache();
    private final Set<String> epgLoading=ConcurrentHashMap.newKeySet();
    private static final ExecutorService epgExec=Executors.newSingleThreadExecutor();
    private static final Handler ui=new Handler(Looper.getMainLooper());

    static class H { ImageView poster; TextView name,meta,fav,badge; ProgressBar progress; }

    public MediaGridAdapter(Context c,LibraryStore s){context=c;store=s;}
    public void setEpg(EpgRequests requests){epgRequests=requests;epgCache.clear();epgLoading.clear();}
    /** Number of language versions behind a grouped search card (MainActivity.languageVersions). */
    int versionCount(MediaEntry e){if(!(context instanceof MainActivity))return 1;java.util.List<MediaEntry> v=((MainActivity)context).languageVersions.get(e.uniqueKey());return v==null?1:v.size();}
    public void set(List<MediaEntry> x,boolean live){items=x==null?new ArrayList<>():x;liveMode=live;notifyDataSetChanged();}
    public void append(List<MediaEntry> more){if(more==null||more.isEmpty())return;if(!(items instanceof ArrayList))items=new ArrayList<>(items);items.addAll(more);notifyDataSetChanged();}
    public int getCount(){return items.size();}
    public MediaEntry getItem(int i){return items.get(i);}
    public long getItemId(int i){return i;}
    int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}

    @Override public View getView(int position,View convertView,ViewGroup parent){
        H h;
        if(convertView==null){
            LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(3),dp(3),dp(3),dp(4));card.setBackgroundResource(R.drawable.bg_card);
            h=new H();
            FrameLayout art=new FrameLayout(context);
            h.poster=new ImageView(context);h.poster.setBackgroundColor(0xFF252A33);art.addView(h.poster,new FrameLayout.LayoutParams(-1,-1));
            h.fav=new TextView(context);h.fav.setTextColor(context.getResources().getColor(R.color.accent));h.fav.setTextSize(17);h.fav.setGravity(Gravity.TOP|Gravity.RIGHT);h.fav.setPadding(0,dp(2),dp(4),0);art.addView(h.fav,new FrameLayout.LayoutParams(-1,-1));h.badge=new TextView(context);h.badge.setTextColor(0xFFFFFFFF);h.badge.setTextSize(10f);h.badge.setPadding(dp(5),dp(2),dp(5),dp(2));android.graphics.drawable.GradientDrawable bd=new android.graphics.drawable.GradientDrawable();bd.setColor(0xCC151A22);bd.setCornerRadius(dp(5));h.badge.setBackground(bd);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);bp.setMargins(dp(4),dp(4),0,0);art.addView(h.badge,bp);
            card.addView(art,new LinearLayout.LayoutParams(-1,dp(126)));
            h.progress=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);h.progress.setMax(100);h.progress.setProgressTintList(android.content.res.ColorStateList.valueOf(context.getResources().getColor(R.color.accent)));h.progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x553C424D));card.addView(h.progress,new LinearLayout.LayoutParams(-1,dp(3)));
            h.name=new TextView(context);h.name.setTextColor(context.getResources().getColor(R.color.text));h.name.setTextSize(13f);h.name.setTypeface(null,android.graphics.Typeface.BOLD);h.name.setMaxLines(3);h.name.setEllipsize(TextUtils.TruncateAt.END);h.name.setPadding(dp(5),dp(4),dp(5),0);card.addView(h.name,new LinearLayout.LayoutParams(-1,-2));
            h.meta=new TextView(context);h.meta.setTextColor(context.getResources().getColor(R.color.muted));h.meta.setTextSize(11f);h.meta.setMaxLines(2);h.meta.setEllipsize(TextUtils.TruncateAt.END);h.meta.setPadding(dp(5),0,dp(5),dp(3));card.addView(h.meta,new LinearLayout.LayoutParams(-1,-2));
            convertView=card;convertView.setTag(h);
        }else h=(H)convertView.getTag();
        MediaEntry e=getItem(position);boolean isLive="live".equals(e.type);
        LinearLayout card=(LinearLayout)convertView;View art=card.getChildAt(0);
        LinearLayout.LayoutParams artLp=(LinearLayout.LayoutParams)art.getLayoutParams();artLp.height=dp(isLive?78:126);art.setLayoutParams(artLp);
        AbsListView.LayoutParams lp=new AbsListView.LayoutParams(-1,-2);convertView.setLayoutParams(lp);
        h.poster.setScaleType(isLive?ImageView.ScaleType.CENTER_INSIDE:ImageView.ScaleType.CENTER_CROP);
        h.name.setText(DisplayText.title(e));h.fav.setText(store.isFavorite(e)?"★":"");String badge=DisplayText.badges(e);int langs=versionCount(e);if(langs>1)badge=(badge.isEmpty()?"":badge+" · ")+"🌐 "+langs;h.badge.setText(badge);h.badge.setVisibility(badge.isEmpty()?View.GONE:View.VISIBLE);
        int pct=store.progressPercent(e);h.progress.setVisibility(!isLive&&pct>0?View.VISIBLE:View.INVISIBLE);h.progress.setProgress(pct);
        String meta=isLive?DisplayText.category(e.group):DisplayText.shortMeta(e);if(meta==null)meta="";if(store.watched(e))meta=(meta.isEmpty()?"":meta+" · ")+UiText.t(context,"watched");h.meta.setText(meta);h.meta.setTag(e.uniqueKey());
        MediaRowAdapter.loadArtwork(h.poster,e.logo,e.name,isLive?220:210,isLive?130:300);
        if(isLive)loadLiveEpg(h.meta,e,meta);
        return convertView;
    }

    private void loadLiveEpg(TextView meta,MediaEntry e,String base){
        final EpgRequests r=epgRequests;if(r==null)return;String k=r.key(e);List<EpgEntry>rows=epgCache.get(k);if(rows!=null){bindLive(meta,e,base,rows);return;}if(!epgLoading.add(k))return;
        epgExec.execute(()->{if(epgRequests!=r)return;try{List<EpgEntry>found=r.load(e);if(epgRequests==r)epgCache.put(k,found);}catch(Exception ex){if(epgRequests==r)epgCache.failed(k);}finally{if(epgRequests==r){epgLoading.remove(k);ui.post(()->{if(epgRequests==r)notifyDataSetChanged();});}}});
    }
    private void bindLive(TextView meta,MediaEntry e,String base,List<EpgEntry> rows){
        long epoch=System.currentTimeMillis()/1000L;EpgEntry current=EpgTimeline.now(rows,epoch),next=EpgTimeline.next(rows,epoch);
        EpgEntry shown=current==null?next:current;if(shown==null)return;
        String text=(base==null||base.isEmpty()?"":DisplayText.category(base)+" · ")+UiText.t(meta.getContext(),current==null?"next":"now")+": "+shown.title;
        if(e.uniqueKey().equals(meta.getTag()))meta.setText(text);
    }


}
