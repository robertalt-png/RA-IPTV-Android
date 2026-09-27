from pathlib import Path
r=Path("source/RA_IPTV_Android_v0.1")

def R(path, old, new, label=""):
    p=r/path
    s=p.read_text()
    if old not in s:
        raise SystemExit("missing "+(label or str(path)))
    p.write_text(s.replace(old,new))

R(Path("app/build.gradle"), "versionCode 32", "versionCode 33")
R(Path("app/build.gradle"), "versionName '0.9.0'", "versionName '0.9.1'")
R(Path("app/src/main/java/com/robertalt/raiptv/SettingsActivity.java"),
  "Nivaro IPTV Player 0.9.0","Nivaro IPTV Player 0.9.1")

# Avoid a custom MediaRouteButton during PlayerActivity inflation. A normal button
# opens the route chooser lazily only when the user actually requests casting.
p=r/"app/src/main/res/layout/activity_player.xml"; s=p.read_text()
old='<androidx.mediarouter.app.MediaRouteButton android:id="@+id/castRouteButton" android:layout_width="48dp" android:layout_height="42dp" app:mediaRouteButtonTint="@android:color/white" android:contentDescription="Afspelen op tv" />'
new='<Button android:id="@+id/castRouteButton" android:layout_width="52dp" android:layout_height="42dp" android:text="TV" android:textAllCaps="false" android:textColor="#FFFFFF" android:backgroundTint="#55000000" android:contentDescription="Afspelen op tv" />'
if old not in s: raise SystemExit("missing cast XML")
p.write_text(s.replace(old,new))

# Transfer large channel/episode queues in-process instead of serializing them into
# an Android Intent/Binder transaction.
store=r/"app/src/main/java/com/robertalt/raiptv/storage/PlaybackQueueStore.java"
store.write_text(r'''package com.robertalt.raiptv.storage;

import com.robertalt.raiptv.model.MediaEntry;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class PlaybackQueueStore {
    private PlaybackQueueStore(){}
    public static final class Payload {
        public final String kind;
        public final ArrayList<MediaEntry> items;
        public final int index;
        Payload(String kind,List<MediaEntry> items,int index){
            this.kind=kind==null?"":kind;
            this.items=new ArrayList<>(items==null?Collections.emptyList():items);
            this.index=index;
        }
    }
    private static final AtomicLong NEXT=new AtomicLong();
    private static final LinkedHashMap<String,Payload> CACHE=new LinkedHashMap<String,Payload>(8,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Payload> e){return size()>4;}
    };
    public static synchronized String put(String kind,List<MediaEntry> items,int index){
        String token=Long.toHexString(System.nanoTime())+"-"+Long.toHexString(NEXT.incrementAndGet());
        CACHE.put(token,new Payload(kind,items,index));
        return token;
    }
    public static synchronized Payload take(String token){
        if(token==null||token.isEmpty())return null;
        return CACHE.remove(token);
    }
}
''')

p=r/"app/src/main/java/com/robertalt/raiptv/MainActivity.java"; s=p.read_text()
old='''    void play(MediaEntry e){library.recent(e);Intent i=new Intent(this,PlayerActivity.class);i.putExtra("media",e);i.putExtra("profileType",profile.type.name());i.putExtra("bridgeUrl",profile.bridgeUrl);i.putExtra("bridgeToken",profile.bridgeToken);if("live".equals(e.type)){ArrayList<MediaEntry>q=new ArrayList<>();for(MediaEntry z:all)if("live".equals(z.type)&&!isAdultLocked(z)){q.add(z);if(q.size()>=250)break;}int at=-1;for(int n=0;n<q.size();n++)if(q.get(n).uniqueKey().equals(e.uniqueKey())){at=n;break;}if(at>=0){i.putExtra("liveQueue",q);i.putExtra("liveIndex",at);}}else if("episode".equals(e.type)&&seriesEpisodeMode){ArrayList<MediaEntry>q=new ArrayList<>();for(MediaEntry z:all)if("episode".equals(z.type)&&!isAdultLocked(z))q.add(z);int at=-1;for(int n=0;n<q.size();n++)if(q.get(n).uniqueKey().equals(e.uniqueKey())){at=n;break;}if(at>=0){i.putExtra("episodeQueue",q);i.putExtra("episodeIndex",at);}}startActivity(i);}
'''
new='''    void play(MediaEntry e){
        library.recent(e);Intent i=new Intent(this,PlayerActivity.class);i.putExtra("media",e);i.putExtra("profileType",profile.type.name());i.putExtra("bridgeUrl",profile.bridgeUrl);i.putExtra("bridgeToken",profile.bridgeToken);
        String queueToken="";
        if("live".equals(e.type)){ArrayList<MediaEntry>q=new ArrayList<>();for(MediaEntry z:all)if("live".equals(z.type)&&!isAdultLocked(z)){q.add(z);if(q.size()>=250)break;}int at=-1;for(int n=0;n<q.size();n++)if(q.get(n).uniqueKey().equals(e.uniqueKey())){at=n;break;}if(at>=0)queueToken=com.robertalt.raiptv.storage.PlaybackQueueStore.put("live",q,at);}
        else if("episode".equals(e.type)&&seriesEpisodeMode){ArrayList<MediaEntry>q=new ArrayList<>();for(MediaEntry z:all)if("episode".equals(z.type)&&!isAdultLocked(z))q.add(z);int at=-1;for(int n=0;n<q.size();n++)if(q.get(n).uniqueKey().equals(e.uniqueKey())){at=n;break;}if(at>=0)queueToken=com.robertalt.raiptv.storage.PlaybackQueueStore.put("episode",q,at);}
        if(!queueToken.isEmpty())i.putExtra("queueToken",queueToken);
        activityPaused=true;
        try{startActivity(i);}catch(RuntimeException ex){activityPaused=false;throw ex;}
    }
'''
if old not in s: raise SystemExit("missing play method")
s=s.replace(old,new)
s=s.replace('try{Thread.sleep(140);}catch(InterruptedException ie)', 'try{Thread.sleep(15);}catch(InterruptedException ie)')
s=s.replace('try{Thread.sleep(700);}catch(InterruptedException ie)', 'try{Thread.sleep(150);}catch(InterruptedException ie)')
p.write_text(s)

# Reuse TCP connections for the hundreds of Xtream category requests.
p=r/"app/src/main/java/com/robertalt/raiptv/net/HttpText.java"; s=p.read_text()
s=s.replace('c.setRequestProperty("Connection", "close");','c.setRequestProperty("Connection", "keep-alive");')
s=s.replace('Nivaro/0.4.0','Nivaro/0.9.1')
p.write_text(s)

p=r/"app/src/main/java/com/robertalt/raiptv/PlayerActivity.java"; s=p.read_text()
s=s.replace('import androidx.mediarouter.app.MediaRouteButton;','import androidx.mediarouter.app.MediaRouteChooserDialog;\nimport androidx.mediarouter.media.MediaRouteSelector;')
s=s.replace('import com.google.android.gms.cast.MediaInfo;','import com.google.android.gms.cast.CastMediaControlIntent;\nimport com.google.android.gms.cast.MediaInfo;')
s=s.replace('import com.google.android.gms.cast.framework.CastButtonFactory;\n','')
s=s.replace('import com.robertalt.raiptv.storage.LibraryStore;','import com.robertalt.raiptv.storage.LibraryStore;\nimport com.robertalt.raiptv.storage.PlaybackQueueStore;')
s=s.replace('Button playPause,rewind,forward,audio,subtitle,pip,channelPrev,channelNext,speed,aspect,sleep,record,favorite;\n    MediaRouteButton castButton;',
            'Button playPause,rewind,forward,audio,subtitle,pip,channelPrev,channelNext,speed,aspect,sleep,record,favorite,castButton;')

old='''        setupCast();
        audio.setText(T("audio"));subtitle.setText(T("subtitles"));aspect.setText(T("fit"));sleep.setText(T("sleep_short"));pip.setContentDescription(T("picture_in_picture"));
        entry=(MediaEntry)getIntent().getSerializableExtra("media");if(entry==null){finish();return;}
        profile.bridgeUrl=getIntent().getStringExtra("bridgeUrl");profile.bridgeToken=getIntent().getStringExtra("bridgeToken");
        Object lq=getIntent().getSerializableExtra("liveQueue");if(lq instanceof ArrayList<?>)try{liveQueue=(ArrayList<MediaEntry>)lq;}catch(Exception ignored){}
        Object eq=getIntent().getSerializableExtra("episodeQueue");if(eq instanceof ArrayList<?>)try{episodeQueue=(ArrayList<MediaEntry>)eq;}catch(Exception ignored){}
        liveIndex=getIntent().getIntExtra("liveIndex",-1);episodeIndex=getIntent().getIntExtra("episodeIndex",-1);
        prepareEntry(entry);updateFavoriteUi();
        if(candidates.isEmpty()){status.setText(T("no_stream_url"));return;}
        wireControls();updateQueueControls();startPreferredPlayer();searchExternalSubtitle();ui.post(tick);showControls();
'''
new='''        audio.setText(T("audio"));subtitle.setText(T("subtitles"));aspect.setText(T("fit"));sleep.setText(T("sleep_short"));pip.setContentDescription(T("picture_in_picture"));
        entry=(MediaEntry)getIntent().getSerializableExtra("media");if(entry==null){finish();return;}
        profile.bridgeUrl=getIntent().getStringExtra("bridgeUrl");profile.bridgeToken=getIntent().getStringExtra("bridgeToken");
        PlaybackQueueStore.Payload qp=PlaybackQueueStore.take(getIntent().getStringExtra("queueToken"));
        if(qp!=null){if("live".equals(qp.kind)){liveQueue=qp.items;liveIndex=qp.index;}else if("episode".equals(qp.kind)){episodeQueue=qp.items;episodeIndex=qp.index;}}
        else{
            Object lq=getIntent().getSerializableExtra("liveQueue");if(lq instanceof ArrayList<?>)try{liveQueue=(ArrayList<MediaEntry>)lq;}catch(Exception ignored){}
            Object eq=getIntent().getSerializableExtra("episodeQueue");if(eq instanceof ArrayList<?>)try{episodeQueue=(ArrayList<MediaEntry>)eq;}catch(Exception ignored){}
            liveIndex=getIntent().getIntExtra("liveIndex",-1);episodeIndex=getIntent().getIntExtra("episodeIndex",-1);
        }
        prepareEntry(entry);updateFavoriteUi();
        if(candidates.isEmpty()){status.setText(T("no_stream_url"));return;}
        wireControls();setupCast();updateQueueControls();startPreferredPlayer();searchExternalSubtitle();ui.post(tick);showControls();
'''
if old not in s: raise SystemExit("missing player onCreate block")
s=s.replace(old,new)

old='''    void setupCast(){
        if(castButton==null)return;try{CastButtonFactory.setUpMediaRouteButton(getApplicationContext(),castButton);castContext=CastContext.getSharedInstance(this);castButton.setVisibility(View.VISIBLE);}catch(Throwable e){castButton.setVisibility(View.GONE);castContext=null;}
    }
'''
new='''    void setupCast(){
        if(castButton==null)return;
        castButton.setVisibility(View.VISIBLE);
        castButton.setOnClickListener(v->showCastChooser());
    }
    void showCastChooser(){
        try{
            if(castContext==null)castContext=CastContext.getSharedInstance(this);
            MediaRouteSelector selector=new MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build();
            MediaRouteChooserDialog dialog=new MediaRouteChooserDialog(this);
            dialog.setRouteSelector(selector);dialog.show();
        }catch(Throwable e){Toast.makeText(this,T("cast_failed"),Toast.LENGTH_SHORT).show();}
    }
'''
if old not in s: raise SystemExit("missing setupCast")
s=s.replace(old,new)

# Session listener registration lazily initializes CastContext only after the player
# screen is alive; a failure simply leaves local playback untouched.
old='''    @Override protected void onStart(){super.onStart();if(castContext!=null)try{castContext.getSessionManager().addSessionManagerListener(castSessionListener,CastSession.class);CastSession c=castContext.getSessionManager().getCurrentCastSession();if(c!=null&&c.isConnected()&&!casting)connectCastSession(c,true);}catch(Exception ignored){}}
'''
new='''    @Override protected void onStart(){super.onStart();try{if(castContext==null)castContext=CastContext.getSharedInstance(this);if(castContext!=null){castContext.getSessionManager().addSessionManagerListener(castSessionListener,CastSession.class);CastSession c=castContext.getSessionManager().getCurrentCastSession();if(c!=null&&c.isConnected()&&!casting)connectCastSession(c,true);}}catch(Throwable ignored){}}
'''
if old not in s: raise SystemExit("missing onStart cast")
s=s.replace(old,new)
p.write_text(s)

print("v0.9.1 stability/speed patch applied")
