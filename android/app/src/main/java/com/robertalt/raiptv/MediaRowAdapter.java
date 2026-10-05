package com.nenotv.player;

import android.content.*;import android.graphics.*;import android.os.*;import android.util.LruCache;import android.view.*;import android.widget.*;
import com.nenotv.player.model.*;import com.nenotv.player.storage.*;import com.nenotv.player.provider.Provider;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;

public class MediaRowAdapter extends BaseAdapter {
    private final LayoutInflater in; private final LibraryStore store; private List<MediaEntry>items=new ArrayList<>();
    private static final ExecutorService images=Executors.newFixedThreadPool(4); private static final ExecutorService epgExec=Executors.newFixedThreadPool(3); private static final Handler ui=new Handler(Looper.getMainLooper());
    private static final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(16*1024){@Override protected int sizeOf(String k,Bitmap b){return Math.max(1,b.getByteCount()/1024);}}; private static final int MAX_IMAGE_BYTES=3*1024*1024;
    private volatile EpgRequests epgRequests; private final EpgRequests.Cache epgCache=new EpgRequests.Cache(); private final Set<String>epgLoading=ConcurrentHashMap.newKeySet();
    static class H{ImageView poster;TextView name,meta,type,fav;}
    public MediaRowAdapter(Context c,LibraryStore s){in=LayoutInflater.from(c);store=s;}
    public void setEpg(EpgRequests requests){epgRequests=requests;epgCache.clear();epgLoading.clear();}
    public void set(List<MediaEntry>x){items=x==null?new ArrayList<>():x;notifyDataSetChanged();}
    public int getCount(){return items.size();} public MediaEntry getItem(int i){return items.get(i);} public long getItemId(int i){return i;}
    public View getView(int i,View v,ViewGroup p){H h;if(v==null){v=in.inflate(R.layout.row_media,p,false);h=new H();h.poster=v.findViewById(R.id.poster);h.name=v.findViewById(R.id.name);h.meta=v.findViewById(R.id.meta);h.type=v.findViewById(R.id.typeChip);h.fav=v.findViewById(R.id.favoriteMark);v.setTag(h);}else h=(H)v.getTag();MediaEntry e=getItem(i);h.name.setText(DisplayText.title(e));h.fav.setText(store.isFavorite(e)?"★":"");String meta=DisplayText.shortMeta(e);if("live".equals(e.type)&&e.group!=null&&!e.group.isEmpty())meta=DisplayText.category(e.group);if(meta==null||meta.trim().isEmpty())meta=typeLabel(e.type);h.meta.setText(meta);h.meta.setTag(e.uniqueKey());h.type.setText(typeLabel(e.type));loadArtwork(h.poster,e.logo,e.name,360,540);if("live".equals(e.type))loadLiveEpg(h.meta,e,meta);return v;}
    private void loadLiveEpg(TextView meta,MediaEntry e,String base){final EpgRequests r=epgRequests;if(r==null)return;String k=r.key(e);List<EpgEntry>rows=epgCache.get(k);if(rows!=null){bindLive(meta,e,base,rows);return;}if(!epgLoading.add(k))return;epgExec.execute(()->{if(epgRequests!=r)return;try{List<EpgEntry>found=r.load(e);if(epgRequests==r)epgCache.put(k,found);}catch(Exception ex){if(epgRequests==r)epgCache.failed(k);}finally{if(epgRequests==r){epgLoading.remove(k);ui.post(()->{if(epgRequests==r)notifyDataSetChanged();});}}});}
    private void bindLive(TextView meta,MediaEntry e,String base,List<EpgEntry> rows){
        long epoch=System.currentTimeMillis()/1000L;EpgEntry current=EpgTimeline.now(rows,epoch),next=EpgTimeline.next(rows,epoch);
        EpgEntry shown=current==null?next:current;if(shown==null)return;
        String text=(base==null||base.isEmpty()?"":DisplayText.category(base)+" · ")+UiText.t(meta.getContext(),current==null?"next":"now")+": "+shown.title;
        if(e.uniqueKey().equals(meta.getTag()))meta.setText(text);
    }

    String typeLabel(String t){android.content.Context c=in.getContext();String x=t.equals("live")?UiText.t(c,"live"):t.equals("vod")?UiText.t(c,"movie"):t.equals("series")?UiText.t(c,"series"):t.equals("episode")?UiText.t(c,"episode"):t;return x.toUpperCase(SettingsStore.appLocale(c));}
    public static void clearArtworkCache(){cache.evictAll();}
    public static void loadArtwork(ImageView image,String url,String label,int targetW,int targetH){
        image.setBackgroundColor(0xFF151A21);image.setContentDescription(label);image.setTag(url);
        final ImageView.ScaleType desired=targetW>targetH?ImageView.ScaleType.CENTER_INSIDE:ImageView.ScaleType.CENTER_CROP;
        int pad=(int)(14*image.getResources().getDisplayMetrics().density);
        image.setPadding(pad,pad,pad,pad);image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);image.setImageResource(R.drawable.ic_nenotv_mark);
        if(url==null||url.trim().isEmpty())return;
        Bitmap cached=cache.get(url);if(cached!=null){image.setPadding(0,0,0,0);image.setScaleType(desired);image.setImageBitmap(cached);return;}
        images.execute(()->{HttpURLConnection c=null;try{
            c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(4000);c.setReadTimeout(5500);c.setUseCaches(true);c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent","SunnyIPTV/0.13.11 (https://sunnyiptv.com; info@sunnyiptv.com)");c.setRequestProperty("Connection","keep-alive");c.setRequestProperty("Accept","image/*,*/*;q=0.8");c.setRequestProperty("Accept-Encoding","identity");
            int code=c.getResponseCode();if(code<200||code>=300)return;byte[]data=readLimited(c.getInputStream());if(data.length==0)return;Bitmap bm=decodeScaled(data,targetW,targetH);if(bm==null)return;cache.put(url,bm);
            image.post(()->{Object tag=image.getTag();if(tag!=null&&tag.equals(url)){image.setPadding(0,0,0,0);image.setScaleType(desired);image.setImageBitmap(bm);}});
        }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}});
    }

    private static byte[] readLimited(InputStream raw)throws IOException{try(InputStream in=raw;ByteArrayOutputStream out=new ByteArrayOutputStream(128*1024)){byte[]buf=new byte[8192];int n,total=0;while((n=in.read(buf))>0){total+=n;if(total>MAX_IMAGE_BYTES)break;out.write(buf,0,n);}return out.toByteArray();}}
    private static Bitmap decodeScaled(byte[]data,int targetW,int targetH){BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,b);int sample=1;while(b.outWidth/sample>targetW*2||b.outHeight/sample>targetH*2)sample*=2;BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=Math.max(1,sample);o.inPreferredConfig=Bitmap.Config.RGB_565;return BitmapFactory.decodeByteArray(data,0,data.length,o);}
}
