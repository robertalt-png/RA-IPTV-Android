package com.nenotv.player.proextras;

import com.nenotv.player.DisplayText;
import com.nenotv.player.DemoPolicy;
import com.nenotv.player.DemoSource;
import com.nenotv.player.ScreenInsets;
import com.nenotv.player.NenoTVCastOptionsProvider;
import com.nenotv.player.ProGate;
import com.nenotv.player.UiText;

import android.app.*;
import android.content.res.Configuration;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.provider.MediaStore;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.fragment.app.FragmentActivity;
import androidx.mediarouter.app.MediaRouteChooserDialog;
import androidx.mediarouter.media.MediaRouteSelector;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.nenotv.player.model.*;
import com.nenotv.player.storage.CrashGuard;
import com.nenotv.player.storage.LibraryStore;
import com.nenotv.player.storage.PlaybackQueueStore;
import com.nenotv.player.cast.CastRelayServer;
import com.nenotv.player.storage.SettingsStore;
import com.nenotv.player.subtitle.SubtitleBridgeClient;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class ProPlayerActivity extends FragmentActivity {
    @Override protected void attachBaseContext(android.content.Context base){super.attachBaseContext(base);com.google.android.play.core.splitcompat.SplitCompat.installActivity(this);}
    FrameLayout root,controls;
    PlayerView media3View;
    TextView title,status,timeText;
    Button playPause,rewind,forward,audio,subtitle,pip,channelPrev,channelNext,speed,aspect,sleep,favorite,castButton,record;
    CastContext castContext; CastSession castSession; RemoteMediaClient castClient;
    MediaRouteChooserDialog castDialog;
    long castPrivacyGeneration;
    final Runnable privacyRevoked=this::revokeCastPrivacy;
    void revokeCastPrivacy(){
        Runnable stop=()->{
            if(destroyed)return;
            if(castDialog!=null){castDialog.dismiss();castDialog=null;}
            if(castContext!=null)try{castContext.getSessionManager().removeSessionManagerListener(castSessionListener,CastSession.class);}catch(Exception ignored){}
            if(entry!=null&&channelPrev!=null)disconnectCastSession(true);else closeCastRelay();
            setupCast();
        };
        if(Looper.myLooper()==Looper.getMainLooper())stop.run();else ui.post(stop);
    }
    CastRelayServer castRelay;
    boolean casting=false,castRemoteConfirmed=false,castRecovering=false,castRelayMode=false,castLocalPausedForRemote=false,castLocalWasPlaying=false;
    int castCandidateIndex=0; long castLocalPosition=0L;
    SeekBar seek;
    ExoPlayer exo;
    MediaEntry entry;
    ArrayList<String>candidates=new ArrayList<>();
    ArrayList<MediaEntry>liveQueue=new ArrayList<>(),episodeQueue=new ArrayList<>();
    int index=0,liveIndex=-1,episodeIndex=-1,aspectMode=0;
    float playbackSpeed=1f;
    boolean destroyed=false,userSeeking=false,wantPlaying=true,recovering=false;
    ExecutorService exec=Executors.newSingleThreadExecutor();
    Handler ui=new Handler(Looper.getMainLooper());
    Profile profile=new Profile();
    com.nenotv.player.provider.PlaybackSourceRoute playbackRoute;
    boolean playbackRevoked=false;
    String castLoadedContentId="";
    File externalSubtitle;
    LibraryStore library;
    long lastWatchPosition=0L,lastProgressAt=0L,watchGraceUntil=0L,lastRecoveryAt=0L,pendingResumeMs=0L,sleepUntil=0L;
    int freezeOnCandidate=0;
    static final long FREEZE_AFTER_MS=12000L;
    static final long START_GRACE_MS=15000L;
    static final long RECOVERY_COOLDOWN_MS=8000L;
    static final long HEALTHY_RESET_MS=30000L;
    String T(String key){return UiText.t(this,key);}
    // Step 3: number zapping and automatic reconnect.
    final com.nenotv.player.core.NumberZap zap=new com.nenotv.player.core.NumberZap();
    TextView zapView; int previousLiveIndex=-1;
    int reconnectAttempt=0; long firstFailureAt=0L; boolean reconnectPending=false; long reconnectResumeMs=0L;
    android.net.ConnectivityManager.NetworkCallback netCallback;
    final Runnable zapCommit=this::commitZap;
    final Runnable reconnectNow=this::reconnect;

    final RemoteMediaClient.Callback castMediaCallback=new RemoteMediaClient.Callback(){
        @Override public void onStatusUpdated(){
            if(!hasCastSession()||castClient==null)return;
            try{MediaStatus ms=castClient.getMediaStatus();if(ms==null)return;if(ms.getPlayerState()==MediaStatus.PLAYER_STATE_IDLE&&ms.getIdleReason()==MediaStatus.IDLE_REASON_ERROR){tryNextCastCandidate();return;}if(ms.getPlayerState()==MediaStatus.PLAYER_STATE_IDLE&&ms.getIdleReason()==MediaStatus.IDLE_REASON_FINISHED){if(castRemoteConfirmed)onMediaEnded();return;}if(ms.getPlayerState()==MediaStatus.PLAYER_STATE_PLAYING){confirmRemotePlayback();status.setText("TV · "+T(castRelayMode?"cast_relay_playing":"cast_playing"));}updatePlayIcon();}catch(Exception ignored){}
        }
    };
    final SessionManagerListener<CastSession> castSessionListener=new SessionManagerListener<CastSession>(){
        @Override public void onSessionStarting(CastSession session){}
        @Override public void onSessionStarted(CastSession session,String sessionId){connectCastSession(session,false);}
        @Override public void onSessionStartFailed(CastSession session,int error){disconnectCastSession(false);}
        @Override public void onSessionSuspended(CastSession session,int reason){}
        @Override public void onSessionResuming(CastSession session,String sessionId){}
        @Override public void onSessionResumed(CastSession session,boolean wasSuspended){connectCastSession(session,true);}
        @Override public void onSessionResumeFailed(CastSession session,int error){disconnectCastSession(false);}
        @Override public void onSessionEnding(CastSession session){}
        @Override public void onSessionEnded(CastSession session,int error){disconnectCastSession(true);}
    };

    Runnable tick=new Runnable(){@Override public void run(){if(destroyed||isFinishing())return;if(DemoPolicy.blockPlayback(ProPlayerActivity.this)){revokePlayback();return;}if(!currentPlaybackRoute())return;updateProgress();antiFreezeTick();ui.postDelayed(this,500);}};
    Runnable hide=new Runnable(){@Override public void run(){controls.animate().alpha(0f).setDuration(220).withEndAction(()->controls.setVisibility(View.GONE));}};
    Runnable sleepStop=()->{if(destroyed)return;status.setText(T("sleep_done"));saveProgress();releasePlayers();finish();};

    @SuppressWarnings("unchecked")
    @Override public void onCreate(Bundle b){
        super.onCreate(b);if(DemoPolicy.blockPlayback(this)||!new com.nenotv.player.storage.EntitlementStore(this).isPro()){finish();return;}CrashGuard.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(R.layout.activity_pro_player);UiText.applyDirection(this);ScreenInsets.player(this);
        library=new LibraryStore(this);
        root=findViewById(R.id.playerRoot);controls=findViewById(R.id.playerControls);media3View=findViewById(R.id.media3View);title=findViewById(R.id.playerTitle);status=findViewById(R.id.playerStatus);timeText=findViewById(R.id.timeText);playPause=findViewById(R.id.playPauseButton);rewind=findViewById(R.id.rewindButton);forward=findViewById(R.id.forwardButton);audio=findViewById(R.id.audioButton);subtitle=findViewById(R.id.subtitleButton);pip=findViewById(R.id.pipButton);seek=findViewById(R.id.seekBar);channelPrev=findViewById(R.id.channelPrevButton);channelNext=findViewById(R.id.channelNextButton);speed=findViewById(R.id.speedButton);aspect=findViewById(R.id.aspectButton);sleep=findViewById(R.id.sleepButton);favorite=findViewById(R.id.favoriteButton);castButton=findViewById(R.id.castRouteButton);record=findViewById(R.id.recordButton);
        audio.setText(T("audio"));subtitle.setText(T("subtitles"));aspect.setText(T("fit"));sleep.setText(T("sleep_short"));pip.setContentDescription(T("picture_in_picture"));
        entry=(MediaEntry)getIntent().getSerializableExtra("media");if(entry==null||!com.nenotv.player.storage.FamilyStore.allowed(this,entry)){finish();return;}
        PlaybackQueueStore.Payload qp=PlaybackQueueStore.take(getIntent().getStringExtra("queueToken"));
        if(qp!=null){if("live".equals(qp.kind)){liveQueue=qp.items;liveIndex=qp.index;}else if("episode".equals(qp.kind)){episodeQueue=qp.items;episodeIndex=qp.index;}}
        else{
            Object lq=getIntent().getSerializableExtra("liveQueue");if(lq instanceof ArrayList<?>)try{liveQueue=(ArrayList<MediaEntry>)lq;}catch(Exception ignored){}
            Object eq=getIntent().getSerializableExtra("episodeQueue");if(eq instanceof ArrayList<?>)try{episodeQueue=(ArrayList<MediaEntry>)eq;}catch(Exception ignored){}
            liveIndex=getIntent().getIntExtra("liveIndex",-1);episodeIndex=getIntent().getIntExtra("episodeIndex",-1);
        }
        liveQueue.removeIf(e->!com.nenotv.player.storage.FamilyStore.allowed(this,e));
        episodeQueue.removeIf(e->!com.nenotv.player.storage.FamilyStore.allowed(this,e));
        liveIndex=-1;episodeIndex=-1;
        for(int n=0;n<liveQueue.size();n++)if(liveQueue.get(n).uniqueKey().equals(entry.uniqueKey()))liveIndex=n;
        for(int n=0;n<episodeQueue.size();n++)if(episodeQueue.get(n).uniqueKey().equals(entry.uniqueKey()))episodeIndex=n;
        if(!prepareEntry(entry))return;updateFavoriteUi();
        if(candidates.isEmpty()){status.setText(T("no_stream_url"));return;}
        if("live".equals(entry.type)&&com.nenotv.player.RecordingService.runningCount()>0)Toast.makeText(this,T("recording_connection_note"),Toast.LENGTH_LONG).show();
        wireControls();createZapOverlay();setupCast();updateQueueControls();startPreferredPlayer();searchExternalSubtitle();ui.post(tick);showControls();
    }

    boolean prepareEntry(MediaEntry e){
        if(playbackRevoked||destroyed||isFinishing())return false;
        if(!com.nenotv.player.storage.FamilyStore.allowed(this,e)){revokePlayback();return false;}
        if(com.nenotv.player.Recordings.isLocalRecording(this,e)){playbackRoute=null;profile=new Profile();}
        else try{playbackRoute=com.nenotv.player.provider.PlaybackSourceRoute.resolve(this,e,new com.nenotv.player.storage.EntitlementStore(this).isPro());profile=playbackRoute.profile();}
        catch(Exception unavailable){revokePlayback();return false;}
        entry=e;title.setText(DisplayText.title(e));candidates=new ArrayList<>(e.candidates);if(candidates.isEmpty()&&e.url!=null&&!e.url.isEmpty())candidates.add(e.url);index=0;freezeOnCandidate=0;pendingResumeMs=0;recovering=false;wantPlaying=true;externalSubtitle=null;lastWatchPosition=0;lastProgressAt=0;watchGraceUntil=0;
        return true;
    }

    boolean currentPlaybackRoute(){
        if(destroyed||isFinishing()||playbackRevoked)return false;
        boolean pro=new com.nenotv.player.storage.EntitlementStore(this).isPro();
        if(pro&&com.nenotv.player.storage.FamilyStore.allowed(this,entry)&&(com.nenotv.player.Recordings.isLocalRecording(this,entry)||playbackRoute!=null&&playbackRoute.isCurrent(true)))return true;
        revokePlayback();return false;
    }
    void revokePlayback(){
        if(playbackRevoked||destroyed)return;
        playbackRevoked=true;wantPlaying=false;recovering=false;
        ui.removeCallbacksAndMessages(null);
        // Do not stop media another controller has subsequently loaded on the receiver.
        try{if(castClient!=null&&!castLoadedContentId.isEmpty()&&castClient.getMediaInfo()!=null&&castLoadedContentId.equals(castClient.getMediaInfo().getContentId()))castClient.stop();}catch(Exception ignored){}
        closeCastRelay();
        releasePlayers();
        status.setText(T("source_unavailable"));Toast.makeText(this,T("source_unavailable"),Toast.LENGTH_LONG).show();finish();
    }

    void updateFavoriteUi(){if(favorite==null||entry==null)return;boolean on=library.isFavorite(entry);favorite.setText(on?"♥":"♡");favorite.setContentDescription(on?T("remove_favorite"):T("add_favorite"));}

    void updateQueueControls(){
        boolean live="live".equals(entry.type)&&liveQueue.size()>1;
        channelPrev.setVisibility(live?View.VISIBLE:View.GONE);channelNext.setVisibility(live?View.VISIBLE:View.GONE);
        speed.setVisibility(live?View.GONE:View.VISIBLE);
        speed.setEnabled(!isCasting());aspect.setEnabled(!isCasting());audio.setEnabled(!isCasting());subtitle.setEnabled(!isCasting());
        seek.setVisibility(live?View.INVISIBLE:View.VISIBLE);
        if(record!=null)record.setVisibility("live".equals(entry.type)&&!DemoSource.isEntry(entry)?View.VISIBLE:View.GONE);
        rewind.setVisibility(live?View.GONE:View.VISIBLE);forward.setVisibility(live?View.GONE:View.VISIBLE);
    }

    void setupCast(){
        if(castButton==null)return;
        castButton.setVisibility(com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this)?View.VISIBLE:View.GONE);
        castButton.setOnClickListener(v->showCastChooser());
    }
    void showCastChooser(){
        if(!com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this))return;
        try{castContext=ProCastPrivacy.get(this);if(castContext==null)return;castPrivacyGeneration=com.nenotv.player.ExtraPrivacySession.generation();castContext.getSessionManager().removeSessionManagerListener(castSessionListener,CastSession.class);castContext.getSessionManager().addSessionManagerListener(castSessionListener,CastSession.class);CastSession current=castContext.getSessionManager().getCurrentCastSession();if(current!=null&&current.isConnected()){connectCastSession(current,false);return;}MediaRouteSelector selector=new MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(NenoTVCastOptionsProvider.receiverApplicationId())).build();castDialog=new MediaRouteChooserDialog(this);castDialog.setRouteSelector(selector);castDialog.show();}catch(Throwable e){Toast.makeText(this,T("cast_failed"),Toast.LENGTH_SHORT).show();}
    }
    boolean hasCastSession(){return com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this)&&castPrivacyGeneration==com.nenotv.player.ExtraPrivacySession.generation()&&!playbackRevoked&&!destroyed&&casting&&castClient!=null&&castSession!=null&&castSession.isConnected();}
    boolean isCasting(){return hasCastSession()&&castRemoteConfirmed;}
    void connectCastSession(CastSession session,boolean resumed){
        if(!com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this)||castPrivacyGeneration!=com.nenotv.player.ExtraPrivacySession.generation())return;
        if(session==null)return;castSession=session;castClient=session.getRemoteMediaClient();if(castClient==null)return;try{castClient.registerCallback(castMediaCallback);}catch(Exception ignored){}
        castLocalPosition=currentLocalPosition();castLocalWasPlaying=isLocalPlaying();casting=true;castRemoteConfirmed=false;castLocalPausedForRemote=false;castRelayMode=false;closeCastRelay();updateQueueControls();
        if(resumed&&castClient.hasMediaSession()&&castClient.isPlaying()){confirmRemotePlayback();status.setText("TV · "+T("cast_playing"));updatePlayIcon();return;}castCandidateIndex=0;loadCastCandidate(castLocalPosition,castLocalWasPlaying);
    }
    void confirmRemotePlayback(){if(castRemoteConfirmed)return;castRemoteConfirmed=true;castLocalPausedForRemote=true;try{releasePlayers();}catch(Exception ignored){}updateQueueControls();updatePlayIcon();}
    void disconnectCastSession(boolean resumeLocal){long pos=0;boolean wasPlaying=false;try{if(castClient!=null){pos=castClient.getApproximateStreamPosition();wasPlaying=castClient.isPlaying();castClient.unregisterCallback(castMediaCallback);}}catch(Exception ignored){}boolean hadRemote=castRemoteConfirmed;casting=false;castRemoteConfirmed=false;castRecovering=false;castRelayMode=false;castClient=null;castSession=null;closeCastRelay();updateQueueControls();if(resumeLocal&&hadRemote&&!destroyed&&entry!=null){pendingResumeMs="live".equals(entry.type)?0:(pos>0?pos:castLocalPosition);wantPlaying=wasPlaying||castLocalWasPlaying;status.setText(T("cast_disconnected"));startMedia3();}}
    long currentLocalPosition(){try{return exo!=null?Math.max(0,exo.getCurrentPosition()):0;}catch(Exception e){return 0;}}
    boolean isLocalPlaying(){try{return exo!=null&&exo.isPlaying();}catch(Exception e){return false;}}
    String castMime(String u){String x=u==null?"":u.toLowerCase(Locale.ROOT);if(x.contains(".m3u8"))return "application/vnd.apple.mpegurl";if(x.contains(".mpd"))return "application/dash+xml";if(x.matches(".*\\.(?:mp4|m4v)(?:[?&#].*)?$"))return "video/mp4";if(x.matches(".*\\.(?:ts|mts|m2ts)(?:[?&#].*)?$"))return "video/mp2t";if(x.matches(".*\\.mkv(?:[?&#].*)?$"))return "video/x-matroska";return "application/octet-stream";}
    ArrayList<String> castCandidates(){
        LinkedHashSet<String> urls=new LinkedHashSet<>();if(!currentPlaybackRoute())return new ArrayList<>();
        addCastUrls(urls,entry);
        if(entry!=null&&"live".equals(entry.type)){
            ArrayList<MediaEntry>alts=new ArrayList<>();
            for(MediaEntry z:liveQueue)if(z!=null&&z!=entry&&sameCastChannel(entry,z)){
                try{com.nenotv.player.provider.PlaybackSourceRoute.resolve(this,z,new com.nenotv.player.storage.EntitlementStore(this).isPro());alts.add(z);}
                catch(Exception unavailable){}
            }
            alts.sort((a,b)->Integer.compare(castVariantRank(a),castVariantRank(b)));
            for(MediaEntry z:alts)addCastUrls(urls,z);
        }
        return new ArrayList<>(urls);
    }
    void addCastUrls(LinkedHashSet<String> out,MediaEntry e){if(e==null)return;ArrayList<String>x=new ArrayList<>(e.candidates);if(x.isEmpty()&&e.url!=null&&!e.url.isEmpty())x.add(e.url);x.sort((a,b)->Integer.compare(castPreference(a),castPreference(b)));out.addAll(x);}
    boolean sameCastChannel(MediaEntry a,MediaEntry b){String x=castChannelKey(a),y=castChannelKey(b);return !x.isEmpty()&&x.equals(y);}
    String castChannelKey(MediaEntry e){String x=e==null||e.name==null?"":e.name.toLowerCase(Locale.ROOT);return x.replaceAll("\\b(?:4k|uhd|fhd|full ?hd|hd|sd|hevc|h265|h264)\\b"," ").replaceAll("[^a-z0-9]+"," ").trim();}
    int castVariantRank(MediaEntry e){String x=((e==null?"":e.name)+" "+(e==null?"":e.group)).toLowerCase(Locale.ROOT);if(x.matches(".*\\bhd\\b.*")&&!x.contains("fhd"))return 0;if(x.contains("fhd")||x.contains("full hd"))return 1;if(x.matches(".*\\bsd\\b.*"))return 2;if(x.contains("4k")||x.contains("uhd"))return 4;return 3;}
    int castPreference(String u){String x=u==null?"":u.toLowerCase(Locale.ROOT);if(x.contains(".m3u8"))return 0;if(x.contains(".mp4")||x.contains(".m4v"))return 1;if(x.contains(".ts")||x.contains(".m2ts"))return 2;if(x.contains(".mkv"))return 4;return 3;}
    void loadCastCandidate(long position,boolean autoplay){if(!hasCastSession())return;ArrayList<String> cc=castCandidates();if(cc.isEmpty())return;if(castCandidateIndex<0||castCandidateIndex>=cc.size())castCandidateIndex=0;String original=cc.get(castCandidateIndex),u=original;castRecovering=false;try{if(castRelayMode){if(castRelay==null)castRelay=new CastRelayServer();u=castRelay.relayUrl(original);}MediaMetadata md=new MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC);md.putString(MediaMetadata.KEY_TITLE,DisplayText.title(entry));String sub=DisplayText.meta(entry);if(sub!=null&&!sub.trim().isEmpty())md.putString(MediaMetadata.KEY_SUBTITLE,sub);MediaInfo.Builder ib=new MediaInfo.Builder(u).setStreamType("live".equals(entry.type)?MediaInfo.STREAM_TYPE_LIVE:MediaInfo.STREAM_TYPE_BUFFERED).setContentType(castMime(original)).setMetadata(md);long d=duration();if(!"live".equals(entry.type)&&d>0)ib.setStreamDuration(d);MediaLoadRequestData.Builder rb=new MediaLoadRequestData.Builder().setMediaInfo(ib.build()).setAutoplay(autoplay);if(!"live".equals(entry.type)&&position>0)rb.setCurrentTime(position);status.setText("TV · "+T(castRelayMode?"cast_try_relay":"cast_connecting"));castLoadedContentId=u;castClient.load(rb.build());}catch(Exception e){tryNextCastCandidate();}}
    void tryNextCastCandidate(){if(castRecovering||!hasCastSession())return;castRecovering=true;ArrayList<String> cc=castCandidates();castCandidateIndex++;if(castCandidateIndex<cc.size()){status.setText(T(castRelayMode?"cast_try_relay":"cast_try_alt"));ui.postDelayed(()->{castRecovering=false;if(hasCastSession())loadCastCandidate("live".equals(entry.type)?0:Math.max(0,castLocalPosition),true);},550);return;}if(!castRelayMode){castRelayMode=true;castCandidateIndex=0;suspendLocalForRelay();status.setText(T("cast_try_relay"));ui.postDelayed(()->{castRecovering=false;if(hasCastSession())loadCastCandidate("live".equals(entry.type)?0:Math.max(0,castLocalPosition),true);},300);return;}castRecovering=false;castRemoteConfirmed=false;closeCastRelay();updateQueueControls();status.setText(T("cast_failed_local"));Toast.makeText(this,T("cast_failed_local"),Toast.LENGTH_LONG).show();if(castLocalPausedForRemote)resumeLocalAfterCastFailure();}
    void suspendLocalForRelay(){if(castLocalPausedForRemote)return;castLocalPausedForRemote=true;castLocalPosition=currentLocalPosition();try{releasePlayers();}catch(Exception ignored){}}
    void resumeLocalAfterCastFailure(){castLocalPausedForRemote=false;wantPlaying=castLocalWasPlaying;pendingResumeMs="live".equals(entry.type)?0:Math.max(0,castLocalPosition);try{if(exo!=null&&castLocalWasPlaying)exo.play();else startMedia3();}catch(Exception ignored){}updatePlayIcon();}
    void closeCastRelay(){if(castRelay!=null){try{castRelay.close();}catch(Exception ignored){}castRelay=null;}}

    void wireControls(){
        root.setOnClickListener(v->{if(controls.getVisibility()==View.VISIBLE)hideControls();else showControls();});
        controls.setOnClickListener(v->hideControls());
        playPause.setOnClickListener(v->{togglePlay();showControls();});
        rewind.setOnClickListener(v->{seekBy(-10000);showControls();});
        forward.setOnClickListener(v->{seekBy(10000);showControls();});
        channelPrev.setOnClickListener(v->{switchLive(-1);showControls();});
        channelNext.setOnClickListener(v->{switchLive(1);showControls();});
        favorite.setOnClickListener(v->{if(entry!=null){library.toggleFavorite(entry);updateFavoriteUi();}showControls();});
        audio.setOnClickListener(v->{showAudioMenu();showControls();});
        subtitle.setOnClickListener(v->{showSubtitleMenu();showControls();});
        speed.setOnClickListener(v->{showSpeedMenu();showControls();});
        aspect.setOnClickListener(v->{cycleAspect();showControls();});
        sleep.setOnClickListener(v->{showSleepMenu();showControls();});
        pip.setOnClickListener(v->enterPip());
        if(record!=null){record.setText("● "+T("record"));record.setOnClickListener(v->{showRecordMenu();showControls();});}
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){userSeeking=true;ui.removeCallbacks(hide);}
            public void onStopTrackingTouch(SeekBar b){userSeeking=false;long d=duration();if(d>0)seekTo(d*b.getProgress()/1000L);showControls();}
            public void onProgressChanged(SeekBar b,int p,boolean from){if(from){long d=duration();if(d>0)timeText.setText(fmt(d*p/1000L)+" / "+fmt(d));}}
        });
    }

    // One player for everything: Media3 with the shared audio setup (passthrough, device decoder, FFmpeg). VLC was removed.
    void startPreferredPlayer(){startMedia3();}
    String audioCodes(){return SettingsStore.csv(SettingsStore.audioLanguageCodes(this));}
    String subtitleCodes(){return "off".equals(SettingsStore.subtitles(this))?"none":SettingsStore.csv(SettingsStore.subtitleLanguageCodes(this));}

    void startMedia3(){
        if(!currentPlaybackRoute())return;
        if(isCasting())return;long resume=currentPosition();releasePlayers();media3View.setVisibility(View.VISIBLE);
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android) SunnyIPTV/0.5.2").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(35000);
        Map<String,String> headers=new HashMap<>();headers.put("Accept","*/*");headers.put("Accept-Encoding","identity");http.setDefaultRequestProperties(headers);
        DefaultDataSource.Factory data=new DefaultDataSource.Factory(this,http);exo=new ExoPlayer.Builder(this,com.nenotv.player.PlayerAudio.renderers(this)).setMediaSourceFactory((DemoSource.isEntry(entry)?DemoSource.mediaSourceFactory(this,entry):new DefaultMediaSourceFactory(data))).build();
        TrackSelectionParameters.Builder ts=exo.getTrackSelectionParameters().buildUpon();String[] ac=SettingsStore.audioLanguageCodes(this),sc=SettingsStore.subtitleLanguageCodes(this);if(ac.length>0)ts.setPreferredAudioLanguages(ac);if("off".equals(SettingsStore.subtitles(this)))ts.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);else{ts.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false);if(sc.length>0)ts.setPreferredTextLanguages(sc);}exo.setTrackSelectionParameters(ts.build());media3View.setPlayer(exo);
        if(media3View.getSubtitleView()!=null){media3View.getSubtitleView().setApplyEmbeddedStyles(false);media3View.getSubtitleView().setApplyEmbeddedFontSizes(false);media3View.getSubtitleView().setStyle(new CaptionStyleCompat(0xFFFFFFFF,0x00000000,0x00000000,CaptionStyleCompat.EDGE_TYPE_OUTLINE,0xFF000000,null));}
        // Resume where casting or freeze recovery left off (this was only done in the removed VLC path).
        long start=pendingResumeMs>0?pendingResumeMs:resume>0?resume:library.progress(entry);pendingResumeMs=0;
        index=0;playMedia3Candidate(start);
        exo.addListener(new Player.Listener(){
            @Override public void onPlayerError(PlaybackException error){runOnUiThread(()->{
                if(destroyed)return;
                // A live stream that fell behind its window restarts at the live edge at once.
                if(com.nenotv.player.core.Reconnect.restartAtLiveEdge(error.errorCode)&&exo!=null){try{exo.seekToDefaultPosition();exo.prepare();exo.play();}catch(Exception ignored){}return;}
                long resumeAt=entry!=null&&!"live".equals(entry.type)?currentLocalPosition():0;
                index++;if(index<candidates.size()){status.setText(T("stream_rejected")+" "+(index+1)+"/"+candidates.size()+"…");playMedia3Candidate(resumeAt);return;}
                if(scheduleReconnect(error.errorCode,resumeAt))return;
                status.setText(T("play_failed")+" "+candidates.size()+" "+T("stream_variants")+": "+error.getErrorCodeName());wantPlaying=false;updatePlayIcon();showControls();});}
            @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){resetReconnect();status.setText("Media3 · "+T("playing"));recovering=false;applyPlaybackSpeed();applyAspect();resetWatchdogGrace();updatePlayIcon();}else if(state==Player.STATE_ENDED)runOnUiThread(ProPlayerActivity.this::onMediaEnded);}
            @Override public void onIsPlayingChanged(boolean isPlaying){updatePlayIcon();}
        });
    }

    void playMedia3Candidate(long resume){
        if(!currentPlaybackRoute())return;
        if(index>=candidates.size())return;status.setText(T("opening_stream")+" "+(index+1)+"/"+candidates.size()+"…");resetWatchdogGrace();MediaItem.Builder b=new MediaItem.Builder().setUri(candidates.get(index));
        if(externalSubtitle!=null){MediaItem.SubtitleConfiguration sc=new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(externalSubtitle)).setMimeType("text/vtt").setLanguage(SettingsStore.resolvedSubtitleLanguage(this)).setLabel(SettingsStore.displayLanguage(this,SettingsStore.resolvedSubtitleLanguage(this))+" "+T("external_subtitle")).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();b.setSubtitleConfigurations(Collections.singletonList(sc));}
        exo.setMediaItem(b.build());exo.prepare();if(resume>0)exo.seekTo(resume);exo.play();
    }

    void onMediaEnded(){if(playbackRevoked||destroyed||isFinishing())return;if(library!=null&&entry!=null)library.markWatched(entry);if("episode".equals(entry.type)&&SettingsStore.autoplay(this)&&episodeQueue.size()>1){switchEpisode(1);return;}wantPlaying=false;updatePlayIcon();}

    void switchLive(int delta){
        if(liveQueue.size()<2)return;switchLiveInternal(delta);
    }

    void switchLiveInternal(int delta){
        saveProgress();int n=liveQueue.size();if(liveIndex<0)liveIndex=0;int before=liveIndex;liveIndex=(liveIndex+delta+n)%n;if(before!=liveIndex)previousLiveIndex=before;resetReconnect();MediaEntry next=liveQueue.get(liveIndex);boolean remote=hasCastSession();if(!remote)releasePlayers();if(!prepareEntry(next))return;library.recent(next);updateFavoriteUi();updateQueueControls();int shown=com.nenotv.player.core.NumberZap.shown(liveIndex,liveNumbers());status.setText(T("zapping")+" · "+(shown>0?shown+" ":"")+DisplayText.title(next));if(remote){castRemoteConfirmed=false;castRelayMode=false;closeCastRelay();castCandidateIndex=0;loadCastCandidate(0,true);}else startPreferredPlayer();
    }

    void switchEpisode(int delta){
        if(episodeQueue.isEmpty())return;int n=episodeQueue.size();if(episodeIndex<0)episodeIndex=0;int nextIndex=episodeIndex+delta;if(nextIndex<0||nextIndex>=n){wantPlaying=false;return;}saveProgress();episodeIndex=nextIndex;MediaEntry next=episodeQueue.get(episodeIndex);boolean remote=hasCastSession();if(!remote)releasePlayers();if(!prepareEntry(next))return;library.recent(next);updateFavoriteUi();updateQueueControls();status.setText(T("next_episode")+" · "+DisplayText.title(next));if(remote){castRemoteConfirmed=false;castRelayMode=false;closeCastRelay();castCandidateIndex=0;loadCastCandidate(library.progress(next),true);}else{startPreferredPlayer();searchExternalSubtitle();}
    }

    void togglePlay(){if(!isCasting()&&reconnectPending){reconnect();return;}if(!isCasting()&&exo!=null&&exo.getPlaybackState()==Player.STATE_IDLE&&!exo.isPlaying()){wantPlaying=true;resetReconnect();index=0;resetWatchdogGrace();playMedia3Candidate("live".equals(entry.type)?0:currentLocalPosition());updatePlayIcon();return;}if(isCasting()){try{castClient.togglePlayback();wantPlaying=!castClient.isPlaying();}catch(Exception ignored){}updatePlayIcon();return;}if(exo!=null){if(exo.isPlaying()){wantPlaying=false;exo.pause();}else{wantPlaying=true;resetWatchdogGrace();exo.play();}}updatePlayIcon();}
    void seekBy(long delta){long d=duration(),p=currentPosition();if(d<=0)return;seekTo(Math.max(0,Math.min(d,p+delta)));}
    void seekTo(long p){if(isCasting())try{castClient.seek(new MediaSeekOptions.Builder().setPosition(p).build());}catch(Exception ignored){}else if(exo!=null)exo.seekTo(p);}
    long currentPosition(){try{return isCasting()?Math.max(0,castClient.getApproximateStreamPosition()):currentLocalPosition();}catch(Exception e){return 0;}}
    long duration(){try{return isCasting()?Math.max(0,castClient.getStreamDuration()):exo!=null&&exo.getDuration()>0?exo.getDuration():0;}catch(Exception e){return 0;}}
    boolean isPlaying(){try{return isCasting()?castClient.isPlaying():isLocalPlaying();}catch(Exception e){return false;}}
    void updatePlayIcon(){playPause.setText(isPlaying()?"❚❚":"▶");}
    void updateProgress(){if(userSeeking)return;long d=duration(),p=currentPosition();if(!"live".equals(entry.type)&&d>0){seek.setEnabled(true);seek.setProgress((int)Math.min(1000,p*1000L/d));timeText.setText(fmt(p)+" / "+fmt(d));}else{seek.setEnabled(false);timeText.setText("live".equals(entry.type)?T("live").toUpperCase(SettingsStore.appLocale(this)):fmt(p));}if(sleepUntil>0){long left=sleepUntil-SystemClock.elapsedRealtime();if(left>0)sleep.setText(T("sleep_short")+" "+Math.max(1,(left+59999)/60000)+"m");}updatePlayIcon();}
    String fmt(long ms){long t=Math.max(0,ms/1000),h=t/3600,m=(t%3600)/60,s=t%60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m,s):String.format(Locale.ROOT,"%02d:%02d",m,s);}

    void resetWatchdogGrace(){long now=SystemClock.elapsedRealtime();watchGraceUntil=now+START_GRACE_MS;lastProgressAt=now;lastWatchPosition=currentPosition();}

    void antiFreezeTick(){
        if(isCasting())return;
        if(destroyed||recovering||userSeeking||!wantPlaying)return;long now=SystemClock.elapsedRealtime();if(now<watchGraceUntil)return;long p=currentPosition();if(p>lastWatchPosition+350){lastWatchPosition=p;lastProgressAt=now;if(now-lastRecoveryAt>HEALTHY_RESET_MS)freezeOnCandidate=0;return;}if(lastProgressAt==0){lastProgressAt=now;lastWatchPosition=p;return;}boolean expectedToMove=exo!=null&&exo.getPlayWhenReady()&&(exo.getPlaybackState()==Player.STATE_BUFFERING||exo.getPlaybackState()==Player.STATE_READY);if(expectedToMove&&now-lastProgressAt>=FREEZE_AFTER_MS)recoverFromFreeze();
    }

    void recoverFromFreeze(){
        long now=SystemClock.elapsedRealtime();if(recovering||now-lastRecoveryAt<RECOVERY_COOLDOWN_MS)return;recovering=true;lastRecoveryAt=now;freezeOnCandidate++;long resume=(entry!=null&&"live".equals(entry.type))?0:currentPosition();status.setText(T("anti_freeze")+" · "+T("recover_stream"));
        if(exo!=null){if(freezeOnCandidate<=1){try{exo.stop();}catch(Exception ignored){}ui.postDelayed(()->{if(!destroyed&&exo!=null){recovering=false;playMedia3Candidate(resume);}},500);return;}freezeOnCandidate=0;index++;if(index<candidates.size()){status.setText(T("anti_freeze")+" · "+T("try_alt_stream"));ui.postDelayed(()->{if(!destroyed&&exo!=null){recovering=false;playMedia3Candidate(resume);}},500);}else{recovering=false;if(!scheduleReconnect(com.nenotv.player.core.Reconnect.IO_NETWORK_CONNECTION_TIMEOUT,resume)){status.setText(T("stream_stuck"));wantPlaying=false;updatePlayIcon();}}}else recovering=false;
    }

    void showAudioMenu(){
        if(isCasting()){Toast.makeText(this,T("cast_tracks_tv"),Toast.LENGTH_SHORT).show();return;}
        if(exo==null){Toast.makeText(this,T("audio_unavailable"),Toast.LENGTH_SHORT).show();return;}
        try{ArrayList<String> names=new ArrayList<>();ArrayList<TrackPick> picks=new ArrayList<>();for(Tracks.Group g:exo.getCurrentTracks().getGroups())if(g.getType()==C.TRACK_TYPE_AUDIO)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){Format f=g.getTrackFormat(i);names.add(formatTrack(f,T("audio")+" "+(names.size()+1)));picks.add(new TrackPick(g,i));}if(names.isEmpty()){Toast.makeText(this,T("no_audio_tracks"),Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(T("audio_track")).setItems(names.toArray(new String[0]),(d,w)->{TrackPick p=picks.get(w);TrackSelectionParameters.Builder b=exo.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).clearOverridesOfType(C.TRACK_TYPE_AUDIO);b.setOverrideForType(new TrackSelectionOverride(p.group.getMediaTrackGroup(),Collections.singletonList(p.index)));exo.setTrackSelectionParameters(b.build());status.setText(T("audio")+": "+names.get(w));}).show();}catch(Exception e){Toast.makeText(this,T("audio_unavailable"),Toast.LENGTH_SHORT).show();}
    }

    void showSubtitleMenu(){
        if(isCasting()){Toast.makeText(this,T("cast_tracks_tv"),Toast.LENGTH_SHORT).show();return;}
        if(exo==null){Toast.makeText(this,T("subtitle_unavailable"),Toast.LENGTH_SHORT).show();return;}
        try{ArrayList<String> names=new ArrayList<>();ArrayList<TrackPick> picks=new ArrayList<>();names.add(T("off"));picks.add(null);for(Tracks.Group g:exo.getCurrentTracks().getGroups())if(g.getType()==C.TRACK_TYPE_TEXT)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){Format f=g.getTrackFormat(i);names.add(formatTrack(f,T("subtitle_track")+" "+names.size()));picks.add(new TrackPick(g,i));}new AlertDialog.Builder(this).setTitle(T("subtitles")).setItems(names.toArray(new String[0]),(d,w)->{TrackSelectionParameters.Builder b=exo.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT);if(w==0)b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);else{TrackPick p=picks.get(w);b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false);b.setOverrideForType(new TrackSelectionOverride(p.group.getMediaTrackGroup(),Collections.singletonList(p.index)));}exo.setTrackSelectionParameters(b.build());status.setText(T("subtitles")+": "+names.get(w));}).show();}catch(Exception e){Toast.makeText(this,T("subtitle_unavailable"),Toast.LENGTH_SHORT).show();}
    }

    static class TrackPick{Tracks.Group group;int index;TrackPick(Tracks.Group g,int i){group=g;index=i;}}
    String cleanTrackName(String s,String fallback){if(s==null||s.trim().isEmpty())return fallback;String x=s.trim();String low=x.toLowerCase(Locale.ROOT);for(String code:new String[]{"nl","nld","dut","en","eng","de","deu","ger","fr","fra","fre","es","spa","it","ita","pt","por","tr","tur","pl","pol","ar","ara"})if(low.equals(code)||low.startsWith(code+" ")||low.startsWith(code+"-")){String lang=SettingsStore.displayLanguage(this,code);return lang+(x.length()>code.length()?" · "+x.substring(code.length()).replaceFirst("^[ -]+",""):"");}return x;}
    String formatTrack(Format f,String fallback){ArrayList<String>x=new ArrayList<>();if(f.label!=null&&!f.label.trim().isEmpty())x.add(f.label.trim());if(f.language!=null&&!f.language.trim().isEmpty()&&!"und".equals(f.language))x.add(SettingsStore.displayLanguage(this,f.language));if(f.channelCount>0)x.add(f.channelCount+"ch");return x.isEmpty()?fallback:TextUtils.join(" · ",x);}

    void showSpeedMenu(){
        final float[] rates={0.5f,0.75f,1f,1.25f,1.5f,2f};String[] labels={"0.5×","0.75×","1.0×","1.25×","1.5×","2.0×"};new AlertDialog.Builder(this).setTitle(T("speed_title")).setItems(labels,(d,w)->{playbackSpeed=rates[w];applyPlaybackSpeed();speed.setText(labels[w]);status.setText(T("speed_label")+": "+labels[w]);}).show();
    }
    void applyPlaybackSpeed(){try{if(exo!=null)exo.setPlaybackSpeed(playbackSpeed);}catch(Exception ignored){}}

    void cycleAspect(){aspectMode=(aspectMode+1)%3;applyAspect();aspect.setText(T("aspect")+": "+(aspectMode==0?T("fit"):aspectMode==1?T("fill"):T("zoom")));}
    void applyAspect(){try{if(media3View!=null){media3View.setResizeMode(aspectMode==0?AspectRatioFrameLayout.RESIZE_MODE_FIT:aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:AspectRatioFrameLayout.RESIZE_MODE_ZOOM);}}catch(Exception ignored){}}

    void showSleepMenu(){String[] items={T("off"),"15 "+T("minutes"),"30 "+T("minutes"),"60 "+T("minutes"),"90 "+T("minutes")};int[] mins={0,15,30,60,90};new AlertDialog.Builder(this).setTitle(T("sleep_timer")).setItems(items,(d,w)->setSleepTimer(mins[w])).show();}
    void setSleepTimer(int minutes){ui.removeCallbacks(sleepStop);if(minutes<=0){sleepUntil=0;sleep.setText(T("sleep_short"));status.setText(T("sleep_off"));return;}sleepUntil=SystemClock.elapsedRealtime()+minutes*60000L;ui.postDelayed(sleepStop,minutes*60000L);sleep.setText(T("sleep_short")+" "+minutes+"m");status.setText(T("sleep_timer")+": "+minutes+" "+T("minutes"));}

    void showControls(){controls.setVisibility(View.VISIBLE);controls.setAlpha(1f);ui.removeCallbacks(hide);ui.postDelayed(hide,4500);}
    void hideControls(){ui.removeCallbacks(hide);hide.run();}
    void enterPip(){if(!SettingsStore.pip(this))return;if(Build.VERSION.SDK_INT>=26&&!isInPictureInPictureMode())try{enterPictureInPictureMode(new PictureInPictureParams.Builder().build());}catch(Exception ignored){}}

    void searchExternalSubtitle(){
        if(entry==null||"live".equals(entry.type)||profile.bridgeUrl==null||profile.bridgeUrl.trim().isEmpty())return;
        MediaEntry requested=entry;com.nenotv.player.provider.PlaybackSourceRoute route=playbackRoute;
        SubtitleBridgeClient c=new SubtitleBridgeClient(route.profile(),SettingsStore.resolvedSubtitleLanguage(this));
        exec.execute(()->{try{
            if(destroyed||!route.isCurrent(new com.nenotv.player.storage.EntitlementStore(this).isPro()))return;
            SubtitleBridgeClient.Result r=c.best(requested);if(r==null)return;
            if(destroyed||!route.isCurrent(new com.nenotv.player.storage.EntitlementStore(this).isPro()))return;
            File f=c.fetchTo(getCacheDir(),r);
            runOnUiThread(()->{if(!destroyed&&!isFinishing()&&playbackRoute==route&&entry==requested&&route.isCurrent(new com.nenotv.player.storage.EntitlementStore(this).isPro())){externalSubtitle=f;attachSubtitle(f,r.label);}else f.delete();});
        }catch(Exception ignored){}});
    }
    void attachSubtitle(File f,String label){if(destroyed)return;if(exo!=null){long pos=exo.getCurrentPosition();boolean was=exo.isPlaying();playMedia3Candidate(pos);if(was)exo.play();status.setText(T("subtitles")+": "+label);}}
    // ---- Recording: record the channel that is playing ----
    void showRecordMenu(){
        if(entry==null||!"live".equals(entry.type))return;
        final int[] mins={30,60,120,180};String[] labels=new String[mins.length];
        for(int i=0;i<mins.length;i++)labels[i]=mins[i]+" "+T("minutes");
        new AlertDialog.Builder(this).setTitle("● "+T("record")+" · "+DisplayText.title(entry)).setItems(labels,(d,w)->{
            com.nenotv.player.Recordings.Planned p=com.nenotv.player.Recordings.recordNow(this,entry,DisplayText.title(entry),mins[w]);
            if(!p.refused.isEmpty()){Toast.makeText(this,T("recording_refused_"+p.refused),Toast.LENGTH_LONG).show();return;}
            Toast.makeText(this,T("recording_started")+"\n"+T("recording_connection_note"),Toast.LENGTH_LONG).show();
        }).setNegativeButton(T("cancel"),null).show();
    }

    // ---- Step 3: number zapping ----
    void createZapOverlay(){
        float d=getResources().getDisplayMetrics().density;int pad=Math.round(14*d);
        zapView=new TextView(this);zapView.setTextSize(30);zapView.setTextColor(Color.WHITE);zapView.setBackgroundColor(0xCC000000);zapView.setPadding(pad*2,pad,pad*2,pad);zapView.setVisibility(View.GONE);zapView.setMaxLines(2);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.TOP|Gravity.END);lp.setMargins(pad*2,pad*2,pad*2,pad*2);root.addView(zapView,lp);
    }
    static int digitFor(int k){if(k>=KeyEvent.KEYCODE_0&&k<=KeyEvent.KEYCODE_9)return k-KeyEvent.KEYCODE_0;if(k>=KeyEvent.KEYCODE_NUMPAD_0&&k<=KeyEvent.KEYCODE_NUMPAD_9)return k-KeyEvent.KEYCODE_NUMPAD_0;return -1;}
    int[] liveNumbers(){int[] n=new int[liveQueue.size()];for(int i=0;i<n.length;i++)n[i]=liveQueue.get(i).number;return n;}
    boolean zapAvailable(){return entry!=null&&"live".equals(entry.type)&&liveQueue.size()>1&&zapView!=null;}
    void handleZapKey(int k,int digit){
        if(digit>=0){if(zap.add(digit)){showZap();ui.removeCallbacks(zapCommit);if(zap.full())commitZap();else ui.postDelayed(zapCommit,com.nenotv.player.core.NumberZap.COMMIT_AFTER_MS);}return;}
        if(k==KeyEvent.KEYCODE_LAST_CHANNEL){if(previousLiveIndex>=0&&previousLiveIndex<liveQueue.size())jumpToLive(previousLiveIndex);return;}
        if(k==KeyEvent.KEYCODE_BACK||k==KeyEvent.KEYCODE_DEL){zap.clear();ui.removeCallbacks(zapCommit);zapView.setVisibility(View.GONE);return;}
        ui.removeCallbacks(zapCommit);commitZap();
    }
    void showZap(){String typed=zap.text();int idx=-1;try{idx=com.nenotv.player.core.NumberZap.resolve(Integer.parseInt(typed),liveNumbers());}catch(Exception ignored){}zapView.setText(typed+(idx>=0?"\n"+DisplayText.title(liveQueue.get(idx)):""));zapView.setVisibility(View.VISIBLE);}
    void commitZap(){
        if(destroyed||zapView==null)return;int number=zap.take();if(number<=0){zapView.setVisibility(View.GONE);return;}
        int idx=com.nenotv.player.core.NumberZap.resolve(number,liveNumbers());
        if(idx<0){zapView.setText(String.format(T("channel_not_found"),String.valueOf(number)));ui.postDelayed(()->{if(zap.isEmpty()&&zapView!=null)zapView.setVisibility(View.GONE);},1600);return;}
        zapView.setVisibility(View.GONE);if(idx!=liveIndex)jumpToLive(idx);
    }
    void jumpToLive(int idx){if(liveQueue.size()<2||idx<0||idx>=liveQueue.size())return;if(liveIndex<0)liveIndex=0;switchLiveInternal(idx-liveIndex);showControls();}

    // ---- Step 3: automatic reconnect ----
    boolean scheduleReconnect(int code,long resumeMs){
        if(destroyed||playbackRevoked||isCasting()||!wantPlaying||entry==null)return false;
        long now=SystemClock.elapsedRealtime();if(firstFailureAt==0)firstFailureAt=now;
        if(!com.nenotv.player.core.Reconnect.retryable(code,reconnectAttempt)||com.nenotv.player.core.Reconnect.giveUp(firstFailureAt,now)){resetReconnect();return false;}
        long delay=com.nenotv.player.core.Reconnect.delayMs(reconnectAttempt);reconnectAttempt++;reconnectPending=true;reconnectResumeMs=Math.max(0,resumeMs);
        status.setText(String.format(T("reconnecting"),String.valueOf(delay/1000),String.valueOf(reconnectAttempt)));showControls();updatePlayIcon();
        ui.removeCallbacks(reconnectNow);ui.postDelayed(reconnectNow,delay);return true;
    }
    void reconnect(){
        if(destroyed||!reconnectPending)return;reconnectPending=false;ui.removeCallbacks(reconnectNow);
        if(!currentPlaybackRoute()||exo==null||isCasting())return;
        index=0;freezeOnCandidate=0;resetWatchdogGrace();playMedia3Candidate("live".equals(entry.type)?0:reconnectResumeMs);
    }
    void resetReconnect(){reconnectAttempt=0;firstFailureAt=0L;reconnectPending=false;ui.removeCallbacks(reconnectNow);}
    void watchNetwork(boolean on){
        try{android.net.ConnectivityManager cm=getSystemService(android.net.ConnectivityManager.class);if(cm==null)return;
            if(on&&netCallback==null){netCallback=new android.net.ConnectivityManager.NetworkCallback(){@Override public void onAvailable(android.net.Network n){ui.post(()->{if(reconnectPending&&!destroyed)reconnect();});}};cm.registerDefaultNetworkCallback(netCallback);}
            else if(!on&&netCallback!=null){cm.unregisterNetworkCallback(netCallback);netCallback=null;}
        }catch(Exception ignored){}
    }

    void releasePlayers(){if(exo!=null){try{exo.release();}catch(Exception ignored){}exo=null;}}
    void saveProgress(){if(library!=null&&entry!=null)library.saveProgress(entry,currentPosition(),duration(),true);}

    /** Number keys go to channel entry before any focused button sees them. */
    @Override public boolean dispatchKeyEvent(KeyEvent ev){
        if(zapAvailable()){
            int k=ev.getKeyCode(),digit=digitFor(k);
            boolean zapKey=digit>=0||k==KeyEvent.KEYCODE_LAST_CHANNEL||(!zap.isEmpty()&&(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_NUMPAD_ENTER||k==KeyEvent.KEYCODE_BACK||k==KeyEvent.KEYCODE_DEL));
            if(zapKey){if(ev.getAction()==KeyEvent.ACTION_DOWN&&ev.getRepeatCount()==0)handleZapKey(k,digit);return true;}
        }
        return super.dispatchKeyEvent(ev);
    }

    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if("live".equals(entry.type)&&liveQueue.size()>1){if(keyCode==KeyEvent.KEYCODE_CHANNEL_UP||keyCode==KeyEvent.KEYCODE_DPAD_UP){switchLive(1);showControls();return true;}if(keyCode==KeyEvent.KEYCODE_CHANNEL_DOWN||keyCode==KeyEvent.KEYCODE_DPAD_DOWN){switchLive(-1);showControls();return true;}}
        if(keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||keyCode==KeyEvent.KEYCODE_SPACE){togglePlay();showControls();return true;}
        if(!"live".equals(entry.type)&&(keyCode==KeyEvent.KEYCODE_MEDIA_REWIND||keyCode==KeyEvent.KEYCODE_DPAD_LEFT)){seekBy(-10000);showControls();return true;}
        if(!"live".equals(entry.type)&&(keyCode==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)){seekBy(10000);showControls();return true;}
        return super.onKeyDown(keyCode,event);
    }

    @Override public void onUserLeaveHint(){super.onUserLeaveHint();enterPip();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    @Override protected void onStart(){super.onStart();watchNetwork(true);com.nenotv.player.ExtraPrivacySession.addListener(privacyRevoked);setupCast();if(!com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this)){privacyRevoked.run();return;}try{castContext=ProCastPrivacy.get(this);castPrivacyGeneration=com.nenotv.player.ExtraPrivacySession.generation();if(castContext!=null){castContext.getSessionManager().addSessionManagerListener(castSessionListener,CastSession.class);CastSession c=castContext.getSessionManager().getCurrentCastSession();if(c!=null&&c.isConnected()&&!casting)connectCastSession(c,true);}}catch(Throwable ignored){}}
    @Override protected void onStop(){watchNetwork(false);if(castContext!=null)try{castContext.getSessionManager().removeSessionManagerListener(castSessionListener,CastSession.class);}catch(Exception ignored){}super.onStop();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)ScreenInsets.player(this);}
    @Override protected void onPause(){saveProgress();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;com.nenotv.player.ExtraPrivacySession.removeListener(privacyRevoked);ui.removeCallbacksAndMessages(null);saveProgress();if(castDialog!=null){castDialog.dismiss();castDialog=null;}try{if(castClient!=null)castClient.unregisterCallback(castMediaCallback);}catch(Exception ignored){}closeCastRelay();releasePlayers();exec.shutdownNow();super.onDestroy();}
}

