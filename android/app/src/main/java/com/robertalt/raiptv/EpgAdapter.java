package com.nenotv.player;

import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
import com.nenotv.player.model.*;import com.nenotv.player.provider.Provider;import com.nenotv.player.storage.EpgStore;import com.nenotv.player.storage.SettingsStore;
import java.util.*;import java.util.concurrent.*;

public class EpgAdapter extends BaseAdapter {
    private final LayoutInflater in; private final EpgStore store; private final Context context; private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService exec=Executors.newFixedThreadPool(2); private final Map<String,List<EpgEntry>> cache=new ConcurrentHashMap<>(); private final Set<String> loading=ConcurrentHashMap.newKeySet();
    private List<MediaEntry> all=new ArrayList<>(), shown=new ArrayList<>(); private Provider provider; private String profileKey=""; private volatile boolean disposed=false;
    static class H{ImageView logo;TextView channel,now,next;ProgressBar progress;}
    public EpgAdapter(Context c,EpgStore s){context=c;in=LayoutInflater.from(c);store=s;}
    public void configure(Provider p,String key){disposed=false;provider=p;profileKey=key==null?"":key;cache.clear();loading.clear();}
    public void set(List<MediaEntry>x){all=x==null?new ArrayList<>():new ArrayList<>(x);shown=new ArrayList<>(all);notifyDataSetChanged();}
    public void filter(String q){String z=q==null?"":q.toLowerCase(Locale.ROOT).trim();shown=new ArrayList<>();for(MediaEntry e:all){if(e==null)continue;String n=e.name==null?"":e.name.toLowerCase(Locale.ROOT);String g=e.group==null?"":e.group.toLowerCase(Locale.ROOT);if(z.isEmpty()||n.contains(z)||g.contains(z))shown.add(e);}notifyDataSetChanged();}
    public int getCount(){return shown.size();} public MediaEntry getItem(int i){return shown.get(i);} public long getItemId(int i){return i;}
    public View getView(int i,View v,ViewGroup p){H h;if(v==null){v=in.inflate(R.layout.row_epg,p,false);h=new H();h.logo=v.findViewById(R.id.epgLogo);h.channel=v.findViewById(R.id.epgChannel);h.now=v.findViewById(R.id.epgNow);h.next=v.findViewById(R.id.epgNext);h.progress=v.findViewById(R.id.epgProgress);v.setTag(h);}else h=(H)v.getTag();MediaEntry e=getItem(i);h.channel.setText(DisplayText.title(e));MediaRowAdapter.loadArtwork(h.logo,e.logo,e.name,180,180);String k=key(e);h.now.setTag(k);h.next.setTag(k);h.now.setText(UiText.t(context,"now")+" · "+UiText.t(context,"epg_loading"));h.next.setText("");h.progress.setProgress(0);List<EpgEntry>rows=cache.get(k);if(rows!=null)bind(h,rows,k);else load(e,k);return v;}
    private String key(MediaEntry e){return profileKey+"|"+e.uniqueKey();}
    private void load(MediaEntry e,String k){if(disposed||provider==null||e==null||!loading.add(k))return;exec.execute(()->{try{List<EpgEntry>r=store.getOrFetch(provider,profileKey,e);cache.put(k,r);}catch(Exception ex){cache.put(k,Collections.emptyList());}finally{loading.remove(k);if(!disposed)ui.post(()->{if(!disposed)notifyDataSetChanged();});}});}
    private void bind(H h,List<EpgEntry> rows,String key){
        long epoch=System.currentTimeMillis()/1000L;EpgEntry current=EpgTimeline.now(rows,epoch),next=EpgTimeline.next(rows,epoch);
        h.now.setText(current==null?UiText.t(context,"no_epg"):UiText.t(context,"now")+"  "+time(current)+current.title);
        h.next.setText(next==null?"":UiText.t(context,"next")+"  "+time(next)+next.title);
        h.progress.setProgress(current==null?0:(int)Math.max(0,Math.min(100,(epoch-current.startEpoch)*100/(current.endEpoch-current.startEpoch))));
    }
    private String time(EpgEntry e){String r=e.range();return r.isEmpty()?"":r+"  ";}
    public void shutdown(){disposed=true;loading.clear();cache.clear();exec.shutdownNow();}
}
