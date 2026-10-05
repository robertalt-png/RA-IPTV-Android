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
import org.videolan.libvlc.*;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class ProPlayerActivity extends FragmentActivity {
    FrameLayout root,controls;
    VLCVideoLayout vlcLayout;
    PlayerView media3View;
    TextView title,status,timeText;
    Button playPause,rewind,forward,audio,subtitle,pip,channelPrev,channelNext,speed,aspect,sleep,record,favorite,castButton;
    CastContext castContext; CastSession castSession; RemoteMediaClient castClient;
    CastRelayServer castRelay;
    boolean casting=false,castRemoteConfirmed=false,castRecovering=false,castRelayMode=false,castLocalPausedForRemote=false,castLocalWasVlc=false,castLocalWasPlaying=false;
    int castCandidateIndex=0; long castLocalPosition=0L;
    SeekBar seek;
    LibVLC libVLC;
    org.videolan.libvlc.MediaPlayer vlc;
    ExoPlayer exo;
    MediaEntry entry;
    ArrayList<String>candidates=new ArrayList<>();
    ArrayList<MediaEntry>liveQueue=new ArrayList<>(),episodeQueue=new ArrayList<>();
    int index=0,liveIndex=-1,episodeIndex=-1,aspectMode=0;
    float playbackSpeed=1f;
    boolean usingVlc=true,destroyed=false,userSeeking=false,wantPlaying=true,recovering=false,recording=false,recordingStarting=false,pendingRecordStart=false;
    ExecutorService exec=Executors.newSingleThreadExecutor();
    Handler ui=new Handler(Looper.getMainLooper());
    Profile profile=new Profile();
    com.nenotv.player.provider.PlaybackSourceRoute playbackRoute;
    boolean playbackRevoked=false;
    String castLoadedContentId="";
    File externalSubtitle;
    LibraryStore library;
    long lastWatchPosition=0L,lastProgressAt=0L,watchGraceUntil=0L,lastRecoveryAt=0L,pendingResumeMs=0L,sleepUntil=0L,recordingStartedAt=0L;
    String recordingChannel="",lastRecordingPath="";
    Runnable afterRecordStop=null;
    int freezeOnCandidate=0;
    static final long FREEZE_AFTER_MS=12000L;
    static final long START_GRACE_MS=15000L;
    static final long RECOVERY_COOLDOWN_MS=8000L;
    static final long HEALTHY_RESET_MS=30000L;
    String T(String key){return UiText.t(this,key);}

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

    Runnable tick=new Runnable(){@Override public void run(){if(destroyed||isFinishing())return;if(DemoPolicy.blockPlayback(ProPlayerActivity.this)){revokePlayback();return;}if(!currentPlaybackRoute())return;updateProgress();updateRecordingUi();antiFreezeTick();ui.postDelayed(this,500);}};
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
        root=findViewById(R.id.playerRoot);controls=findViewById(R.id.playerControls);vlcLayout=findViewById(R.id.vlcLayout);media3View=findViewById(R.id.media3View);title=findViewById(R.id.playerTitle);status=findViewById(R.id.playerStatus);timeText=findViewById(R.id.timeText);playPause=findViewById(R.id.playPauseButton);rewind=findViewById(R.id.rewindButton);forward=findViewById(R.id.forwardButton);audio=findViewById(R.id.audioButton);subtitle=findViewById(R.id.subtitleButton);pip=findViewById(R.id.pipButton);seek=findViewById(R.id.seekBar);channelPrev=findViewById(R.id.channelPrevButton);channelNext=findViewById(R.id.channelNextButton);speed=findViewById(R.id.speedButton);aspect=findViewById(R.id.aspectButton);sleep=findViewById(R.id.sleepButton);record=findViewById(R.id.recordButton);favorite=findViewById(R.id.favoriteButton);castButton=findViewById(R.id.castRouteButton);
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
        wireControls();setupCast();updateQueueControls();startPreferredPlayer();searchExternalSubtitle();ui.post(tick);showControls();
    }

    boolean prepareEntry(MediaEntry e){
        if(playbackRevoked||destroyed||isFinishing())return false;
        if(!com.nenotv.player.storage.FamilyStore.allowed(this,e)){revokePlayback();return false;}
        try{playbackRoute=com.nenotv.player.provider.PlaybackSourceRoute.resolve(this,e,new com.nenotv.player.storage.EntitlementStore(this).isPro());profile=playbackRoute.profile();}
        catch(Exception unavailable){revokePlayback();return false;}
        entry=e;title.setText(DisplayText.title(e));candidates=new ArrayList<>(e.candidates);if(candidates.isEmpty()&&e.url!=null&&!e.url.isEmpty())candidates.add(e.url);index=0;freezeOnCandidate=0;pendingResumeMs=0;recovering=false;wantPlaying=true;externalSubtitle=null;lastWatchPosition=0;lastProgressAt=0;watchGraceUntil=0;
        return true;
    }

    boolean currentPlaybackRoute(){
        if(destroyed||isFinishing()||playbackRevoked)return false;
        boolean pro=new com.nenotv.player.storage.EntitlementStore(this).isPro();
        if(pro&&com.nenotv.player.storage.FamilyStore.allowed(this,entry)&&playbackRoute!=null&&playbackRoute.isCurrent(true))return true;
        revokePlayback();return false;
    }
    void revokePlayback(){
        if(playbackRevoked||destroyed)return;
        playbackRevoked=true;wantPlaying=false;recovering=false;pendingRecordStart=false;afterRecordStop=null;
        ui.removeCallbacksAndMessages(null);
        // Do not stop media another controller has subsequently loaded on the receiver.
        try{if(castClient!=null&&!castLoadedContentId.isEmpty()&&castClient.getMediaInfo()!=null&&castLoadedContentId.equals(castClient.getMediaInfo().getContentId()))castClient.stop();}catch(Exception ignored){}
        closeCastRelay();
        if(vlc!=null&&(recording||recordingStarting))try{vlc.record(null);}catch(Exception ignored){}
        recording=false;recordingStarting=false;releasePlayers();
        status.setText(T("source_unavailable"));Toast.makeText(this,T("source_unavailable"),Toast.LENGTH_LONG).show();finish();
    }

    void updateFavoriteUi(){if(favorite==null||entry==null)return;boolean on=library.isFavorite(entry);favorite.setText(on?"♥":"♡");favorite.setContentDescription(on?T("remove_favorite"):T("add_favorite"));}

    void updateQueueControls(){
        boolean live="live".equals(entry.type)&&liveQueue.size()>1;
        channelPrev.setVisibility(live?View.VISIBLE:View.GONE);channelNext.setVisibility(live?View.VISIBLE:View.GONE);
        speed.setVisibility(live?View.GONE:View.VISIBLE);
        record.setVisibility("live".equals(entry.type)&&!isCasting()?View.VISIBLE:View.GONE);
        speed.setEnabled(!isCasting());aspect.setEnabled(!isCasting());audio.setEnabled(!isCasting());subtitle.setEnabled(!isCasting());
        if(!"live".equals(entry.type)){recording=false;recordingStarting=false;pendingRecordStart=false;record.setText("● REC");record.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55000000));}
        seek.setVisibility(live?View.INVISIBLE:View.VISIBLE);
        rewind.setVisibility(live?View.GONE:View.VISIBLE);forward.setVisibility(live?View.GONE:View.VISIBLE);
    }

    void setupCast(){
        if(castButton==null)return;
        castButton.setVisibility(com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this)?View.VISIBLE:View.GONE);
        castButton.setOnClickListener(v->showCastChooser());
    }
    void showCastChooser(){
        if(!com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this))return;
        try{if(castContext==null)castContext=CastContext.getSharedInstance(this);CastSession current=castContext.getSessionManager().getCurrentCastSession();if(current!=null&&current.isConnected()){connectCastSession(current,false);return;}MediaRouteSelector selector=new MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(NenoTVCastOptionsProvider.receiverApplicationId())).build();MediaRouteChooserDialog dialog=new MediaRouteChooserDialog(this);dialog.setRouteSelector(selector);dialog.show();}catch(Throwable e){Toast.makeText(this,T("cast_failed"),Toast.LENGTH_SHORT).show();}
    }
    boolean hasCastSession(){return !playbackRevoked&&!destroyed&&casting&&castClient!=null&&castSession!=null&&castSession.isConnected();}
    boolean isCasting(){return hasCastSession()&&castRemoteConfirmed;}
    void connectCastSession(CastSession session,boolean resumed){
        if(!com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this))return;
        if(session==null)return;castSession=session;castClient=session.getRemoteMediaClient();if(castClient==null)return;try{castClient.registerCallback(castMediaCallback);}catch(Exception ignored){}
        castLocalPosition=currentLocalPosition();castLocalWasPlaying=isLocalPlaying();castLocalWasVlc=usingVlc;casting=true;castRemoteConfirmed=false;castLocalPausedForRemote=false;castRelayMode=false;closeCastRelay();updateQueueControls();
        if(resumed&&castClient.hasMediaSession()&&castClient.isPlaying()){confirmRemotePlayback();status.setText("TV · "+T("cast_playing"));updatePlayIcon();return;}castCandidateIndex=0;loadCastCandidate(castLocalPosition,castLocalWasPlaying);
    }
    void confirmRemotePlayback(){if(castRemoteConfirmed)return;castRemoteConfirmed=true;castLocalPausedForRemote=true;try{releasePlayers();}catch(Exception ignored){}updateQueueControls();updatePlayIcon();}
    void disconnectCastSession(boolean resumeLocal){long pos=0;boolean wasPlaying=false;try{if(castClient!=null){pos=castClient.getApproximateStreamPosition();wasPlaying=castClient.isPlaying();castClient.unregisterCallback(castMediaCallback);}}catch(Exception ignored){}boolean hadRemote=castRemoteConfirmed;casting=false;castRemoteConfirmed=false;castRecovering=false;castRelayMode=false;castClient=null;castSession=null;closeCastRelay();updateQueueControls();if(resumeLocal&&hadRemote&&!destroyed&&entry!=null){pendingResumeMs="live".equals(entry.type)?0:(pos>0?pos:castLocalPosition);wantPlaying=wasPlaying||castLocalWasPlaying;status.setText(T("cast_disconnected"));if(castLocalWasVlc)startVlc();else startMedia3();}}
    long currentLocalPosition(){try{return usingVlc&&vlc!=null?Math.max(0,vlc.getTime()):exo!=null?Math.max(0,exo.getCurrentPosition()):0;}catch(Exception e){return 0;}}
    boolean isLocalPlaying(){try{return usingVlc&&vlc!=null?vlc.isPlaying():exo!=null&&exo.isPlaying();}catch(Exception e){return false;}}
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
    void resumeLocalAfterCastFailure(){castLocalPausedForRemote=false;wantPlaying=castLocalWasPlaying;pendingResumeMs="live".equals(entry.type)?0:Math.max(0,castLocalPosition);try{if(castLocalWasVlc){if(vlc!=null&&castLocalWasPlaying)vlc.play();else startVlc();}else{if(exo!=null&&castLocalWasPlaying)exo.play();else startMedia3();}}catch(Exception ignored){}updatePlayIcon();}
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
        record.setOnClickListener(v->{toggleRecording();showControls();});
        pip.setOnClickListener(v->enterPip());
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){userSeeking=true;ui.removeCallbacks(hide);}
            public void onStopTrackingTouch(SeekBar b){userSeeking=false;long d=duration();if(d>0)seekTo(d*b.getProgress()/1000L);showControls();}
            public void onProgressChanged(SeekBar b,int p,boolean from){if(from){long d=duration();if(d>0)timeText.setText(fmt(d*p/1000L)+" / "+fmt(d));}}
        });
    }

    void startPreferredPlayer(){if(DemoSource.isEntry(entry)){startMedia3();return;}if("media3".equals(SettingsStore.player(this)))startMedia3();else startVlc();}
    String audioCodes(){return SettingsStore.csv(SettingsStore.audioLanguageCodes(this));}
    String subtitleCodes(){return "off".equals(SettingsStore.subtitles(this))?"none":SettingsStore.csv(SettingsStore.subtitleLanguageCodes(this));}

    void startVlc(){
        if(!currentPlaybackRoute())return;
        if(isCasting())return;releasePlayers();usingVlc=true;vlcLayout.setVisibility(View.VISIBLE);media3View.setVisibility(View.GONE);
        ArrayList<String>args=new ArrayList<>(Arrays.asList("--network-caching="+SettingsStore.bufferMs(this),"--http-reconnect","--no-video-title-show","--freetype-color=16777215","--freetype-outline-color=0","--freetype-outline-opacity=255","--freetype-outline-thickness=2","--freetype-shadow-opacity=0","--freetype-background-opacity=0"));
        String ap=audioCodes(),sp=subtitleCodes();if(!ap.isEmpty())args.add("--audio-language="+ap);if(!sp.isEmpty())args.add("--sub-language="+sp);
        libVLC=new LibVLC(this,args);vlc=new org.videolan.libvlc.MediaPlayer(libVLC);vlc.attachViews(vlcLayout,null,true,false);
        vlc.setEventListener(ev->{
            if(ev.type==org.videolan.libvlc.MediaPlayer.Event.EncounteredError)runOnUiThread(this::vlcFailed);
            else if(ev.type==org.videolan.libvlc.MediaPlayer.Event.Playing)runOnUiThread(()->{status.setText("VLC · "+T("playing"));long resume=pendingResumeMs>0?pendingResumeMs:library.progress(entry);if(resume>10000&&entry!=null&&!"live".equals(entry.type)){try{if(vlc.getTime()<5000)vlc.setTime(resume);}catch(Exception ignored){}}pendingResumeMs=0;recovering=false;applyPlaybackSpeed();applyAspect();resetWatchdogGrace();updatePlayIcon();if(pendingRecordStart){pendingRecordStart=false;ui.postDelayed(this::startRecording,250);}});
            else if(ev.type==org.videolan.libvlc.MediaPlayer.Event.RecordChanged)runOnUiThread(()->onRecordChanged(ev.getRecording(),ev.getRecordPath()));
            else if(ev.type==org.videolan.libvlc.MediaPlayer.Event.EndReached)runOnUiThread(this::onMediaEnded);
            else if(ev.type==org.videolan.libvlc.MediaPlayer.Event.Paused||ev.type==org.videolan.libvlc.MediaPlayer.Event.Stopped)runOnUiThread(this::updatePlayIcon);
        });
        playVlcCandidate();
    }

    void playVlcCandidate(){
        if(!currentPlaybackRoute())return;
        if(index>=candidates.size()){if("vlc".equals(SettingsStore.player(this))){status.setText(T("vlc_cannot_open"));wantPlaying=false;}else startMedia3();return;}
        String u=candidates.get(index);status.setText(T("connecting_vlc"));resetWatchdogGrace();Media m=new Media(libVLC,Uri.parse(u));if(DemoSource.isEntry(entry))m.addOption(":http-user-agent=SunnyIPTV/0.13.11 (https://sunnyiptv.com; info@sunnyiptv.com)");m.setHWDecoderEnabled(true,false);m.addOption(":network-caching="+SettingsStore.bufferMs(this));m.addOption(":http-reconnect");String ap=audioCodes(),sp=subtitleCodes();if(!ap.isEmpty())m.addOption(":audio-language="+ap);if(!sp.isEmpty())m.addOption(":sub-language="+sp);if(externalSubtitle!=null)m.addOption(":sub-file="+externalSubtitle.getAbsolutePath());vlc.setMedia(m);m.release();vlc.play();
    }

    void vlcFailed(){recovering=false;freezeOnCandidate=0;index++;if(index<candidates.size()){status.setText(T("other_stream"));ui.postDelayed(this::playVlcCandidate,500);}else if("vlc".equals(SettingsStore.player(this))){status.setText(T("vlc_cannot_open"));wantPlaying=false;}else{status.setText(T("vlc_media3"));startMedia3();}}

    void startMedia3(){
        if(!currentPlaybackRoute())return;
        if(isCasting())return;long resume=currentPosition();releasePlayers();usingVlc=false;media3View.setVisibility(View.VISIBLE);vlcLayout.setVisibility(View.GONE);
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android) SunnyIPTV/0.5.2").setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(35000);
        Map<String,String> headers=new HashMap<>();headers.put("Accept","*/*");headers.put("Accept-Encoding","identity");http.setDefaultRequestProperties(headers);
        DefaultDataSource.Factory data=new DefaultDataSource.Factory(this,http);exo=new ExoPlayer.Builder(this).setMediaSourceFactory((DemoSource.isEntry(entry)?DemoSource.mediaSourceFactory(this,entry):new DefaultMediaSourceFactory(data))).build();
        TrackSelectionParameters.Builder ts=exo.getTrackSelectionParameters().buildUpon();String[] ac=SettingsStore.audioLanguageCodes(this),sc=SettingsStore.subtitleLanguageCodes(this);if(ac.length>0)ts.setPreferredAudioLanguages(ac);if("off".equals(SettingsStore.subtitles(this)))ts.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);else{ts.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false);if(sc.length>0)ts.setPreferredTextLanguages(sc);}exo.setTrackSelectionParameters(ts.build());media3View.setPlayer(exo);
        if(media3View.getSubtitleView()!=null){media3View.getSubtitleView().setApplyEmbeddedStyles(false);media3View.getSubtitleView().setApplyEmbeddedFontSizes(false);media3View.getSubtitleView().setStyle(new CaptionStyleCompat(0xFFFFFFFF,0x00000000,0x00000000,CaptionStyleCompat.EDGE_TYPE_OUTLINE,0xFF000000,null));}
        index=0;playMedia3Candidate(resume>0?resume:library.progress(entry));
        exo.addListener(new Player.Listener(){
            @Override public void onPlayerError(PlaybackException error){runOnUiThread(()->{index++;if(index<candidates.size()){status.setText(T("stream_rejected")+" "+(index+1)+"/"+candidates.size()+"…");playMedia3Candidate(0);}else{status.setText(T("play_failed")+" "+candidates.size()+" "+T("stream_variants")+": "+error.getErrorCodeName());wantPlaying=false;}});}
            @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){status.setText("Media3 · "+T("playing"));recovering=false;applyPlaybackSpeed();applyAspect();resetWatchdogGrace();updatePlayIcon();}else if(state==Player.STATE_ENDED)runOnUiThread(ProPlayerActivity.this::onMediaEnded);}
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
        if(liveQueue.size()<2)return;if(recording||recordingStarting){requestStopRecording(()->switchLiveInternal(delta),T("record_finish_zap"));return;}switchLiveInternal(delta);
    }

    void switchLiveInternal(int delta){
        saveProgress();int n=liveQueue.size();if(liveIndex<0)liveIndex=0;liveIndex=(liveIndex+delta+n)%n;MediaEntry next=liveQueue.get(liveIndex);boolean remote=hasCastSession();if(!remote)releasePlayers();if(!prepareEntry(next))return;library.recent(next);updateFavoriteUi();updateQueueControls();status.setText(T("zapping")+" · "+DisplayText.title(next));if(remote){castRemoteConfirmed=false;castRelayMode=false;closeCastRelay();castCandidateIndex=0;loadCastCandidate(0,true);}else startPreferredPlayer();
    }

    void switchEpisode(int delta){
        if(episodeQueue.isEmpty())return;int n=episodeQueue.size();if(episodeIndex<0)episodeIndex=0;int nextIndex=episodeIndex+delta;if(nextIndex<0||nextIndex>=n){wantPlaying=false;return;}saveProgress();episodeIndex=nextIndex;MediaEntry next=episodeQueue.get(episodeIndex);boolean remote=hasCastSession();if(!remote)releasePlayers();if(!prepareEntry(next))return;library.recent(next);updateFavoriteUi();updateQueueControls();status.setText(T("next_episode")+" · "+DisplayText.title(next));if(remote){castRemoteConfirmed=false;castRelayMode=false;closeCastRelay();castCandidateIndex=0;loadCastCandidate(library.progress(next),true);}else{startPreferredPlayer();searchExternalSubtitle();}
    }

    void togglePlay(){if(isCasting()){try{castClient.togglePlayback();wantPlaying=!castClient.isPlaying();}catch(Exception ignored){}updatePlayIcon();return;}if(usingVlc&&vlc!=null){if(vlc.isPlaying()){wantPlaying=false;vlc.pause();}else{wantPlaying=true;resetWatchdogGrace();vlc.play();}}else if(exo!=null){if(exo.isPlaying()){wantPlaying=false;exo.pause();}else{wantPlaying=true;resetWatchdogGrace();exo.play();}}updatePlayIcon();}
    void seekBy(long delta){long d=duration(),p=currentPosition();if(d<=0)return;seekTo(Math.max(0,Math.min(d,p+delta)));}
    void seekTo(long p){if(isCasting())try{castClient.seek(new MediaSeekOptions.Builder().setPosition(p).build());}catch(Exception ignored){}else if(usingVlc&&vlc!=null)try{vlc.setTime(p);}catch(Exception ignored){}else if(exo!=null)exo.seekTo(p);}
    long currentPosition(){try{return isCasting()?Math.max(0,castClient.getApproximateStreamPosition()):currentLocalPosition();}catch(Exception e){return 0;}}
    long duration(){try{return isCasting()?Math.max(0,castClient.getStreamDuration()):usingVlc&&vlc!=null?Math.max(0,vlc.getLength()):exo!=null&&exo.getDuration()>0?exo.getDuration():0;}catch(Exception e){return 0;}}
    boolean isPlaying(){try{return isCasting()?castClient.isPlaying():isLocalPlaying();}catch(Exception e){return false;}}
    void updatePlayIcon(){playPause.setText(isPlaying()?"❚❚":"▶");}
    void updateProgress(){if(userSeeking)return;long d=duration(),p=currentPosition();if(!"live".equals(entry.type)&&d>0){seek.setEnabled(true);seek.setProgress((int)Math.min(1000,p*1000L/d));timeText.setText(fmt(p)+" / "+fmt(d));}else{seek.setEnabled(false);timeText.setText("live".equals(entry.type)?T("live").toUpperCase(SettingsStore.appLocale(this)):fmt(p));}if(sleepUntil>0){long left=sleepUntil-SystemClock.elapsedRealtime();if(left>0)sleep.setText(T("sleep_short")+" "+Math.max(1,(left+59999)/60000)+"m");}updatePlayIcon();}
    String fmt(long ms){long t=Math.max(0,ms/1000),h=t/3600,m=(t%3600)/60,s=t%60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m,s):String.format(Locale.ROOT,"%02d:%02d",m,s);}

    void resetWatchdogGrace(){long now=SystemClock.elapsedRealtime();watchGraceUntil=now+START_GRACE_MS;lastProgressAt=now;lastWatchPosition=currentPosition();}

    void antiFreezeTick(){
        if(isCasting())return;
        if(destroyed||recovering||userSeeking||!wantPlaying)return;long now=SystemClock.elapsedRealtime();if(now<watchGraceUntil)return;long p=currentPosition();if(p>lastWatchPosition+350){lastWatchPosition=p;lastProgressAt=now;if(now-lastRecoveryAt>HEALTHY_RESET_MS)freezeOnCandidate=0;return;}if(lastProgressAt==0){lastProgressAt=now;lastWatchPosition=p;return;}boolean expectedToMove;if(usingVlc){expectedToMove=vlc!=null;}else{expectedToMove=exo!=null&&exo.getPlayWhenReady()&&(exo.getPlaybackState()==Player.STATE_BUFFERING||exo.getPlaybackState()==Player.STATE_READY);}if(expectedToMove&&now-lastProgressAt>=FREEZE_AFTER_MS)recoverFromFreeze();
    }

    void recoverFromFreeze(){
        long now=SystemClock.elapsedRealtime();if(recovering||now-lastRecoveryAt<RECOVERY_COOLDOWN_MS)return;recovering=true;lastRecoveryAt=now;freezeOnCandidate++;long resume=(entry!=null&&"live".equals(entry.type))?0:currentPosition();status.setText(T("anti_freeze")+" · "+T("recover_stream"));
        if(usingVlc){if(freezeOnCandidate<=1&&vlc!=null){pendingResumeMs=resume;try{vlc.stop();}catch(Exception ignored){}ui.postDelayed(()->{if(!destroyed){recovering=false;playVlcCandidate();}},600);return;}freezeOnCandidate=0;index++;pendingResumeMs=resume;if(index<candidates.size()){status.setText(T("anti_freeze")+" · "+T("try_alt_stream"));ui.postDelayed(()->{if(!destroyed){recovering=false;playVlcCandidate();}},500);}else if(!"vlc".equals(SettingsStore.player(this))){index=0;status.setText(T("anti_freeze")+" · "+T("switch_media3"));recovering=false;startMedia3();}else{recovering=false;wantPlaying=false;status.setText(T("stream_stuck"));}}
        else if(exo!=null){if(freezeOnCandidate<=1){try{exo.stop();}catch(Exception ignored){}ui.postDelayed(()->{if(!destroyed&&exo!=null){recovering=false;playMedia3Candidate(resume);}},500);return;}freezeOnCandidate=0;index++;if(index<candidates.size()){status.setText(T("anti_freeze")+" · "+T("try_alt_stream"));ui.postDelayed(()->{if(!destroyed&&exo!=null){recovering=false;playMedia3Candidate(resume);}},500);}else{recovering=false;status.setText(T("stream_stuck"));wantPlaying=false;updatePlayIcon();}}else recovering=false;
    }

    void showAudioMenu(){
        if(isCasting()){Toast.makeText(this,T("cast_tracks_tv"),Toast.LENGTH_SHORT).show();return;}
        if(usingVlc&&vlc!=null){try{org.videolan.libvlc.MediaPlayer.TrackDescription[] t=vlc.getAudioTracks();if(t==null||t.length==0){Toast.makeText(this,T("no_audio_tracks"),Toast.LENGTH_SHORT).show();return;}String[] names=new String[t.length];for(int i=0;i<t.length;i++)names[i]=cleanTrackName(t[i].name,T("audio")+" "+(i+1));new AlertDialog.Builder(this).setTitle(T("audio_track")).setItems(names,(d,w)->{vlc.setAudioTrack(t[w].id);status.setText(T("audio")+": "+names[w]);}).show();return;}catch(Exception ignored){}}
        if(exo==null){Toast.makeText(this,T("audio_unavailable"),Toast.LENGTH_SHORT).show();return;}
        try{ArrayList<String> names=new ArrayList<>();ArrayList<TrackPick> picks=new ArrayList<>();for(Tracks.Group g:exo.getCurrentTracks().getGroups())if(g.getType()==C.TRACK_TYPE_AUDIO)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){Format f=g.getTrackFormat(i);names.add(formatTrack(f,T("audio")+" "+(names.size()+1)));picks.add(new TrackPick(g,i));}if(names.isEmpty()){Toast.makeText(this,T("no_audio_tracks"),Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(T("audio_track")).setItems(names.toArray(new String[0]),(d,w)->{TrackPick p=picks.get(w);TrackSelectionParameters.Builder b=exo.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,false).clearOverridesOfType(C.TRACK_TYPE_AUDIO);b.setOverrideForType(new TrackSelectionOverride(p.group.getMediaTrackGroup(),Collections.singletonList(p.index)));exo.setTrackSelectionParameters(b.build());status.setText(T("audio")+": "+names.get(w));}).show();}catch(Exception e){Toast.makeText(this,T("audio_unavailable"),Toast.LENGTH_SHORT).show();}
    }

    void showSubtitleMenu(){
        if(isCasting()){Toast.makeText(this,T("cast_tracks_tv"),Toast.LENGTH_SHORT).show();return;}
        if(usingVlc&&vlc!=null){try{org.videolan.libvlc.MediaPlayer.TrackDescription[] t=vlc.getSpuTracks();if(t==null||t.length==0){Toast.makeText(this,externalSubtitle!=null?T("external_sub_active"):T("no_subtitles"),Toast.LENGTH_SHORT).show();return;}String[] names=new String[t.length];for(int i=0;i<t.length;i++)names[i]=cleanTrackName(t[i].name,T("subtitle_track")+" "+(i+1));new AlertDialog.Builder(this).setTitle(T("subtitles")).setItems(names,(d,w)->{vlc.setSpuTrack(t[w].id);status.setText(T("subtitles")+": "+names[w]);}).show();return;}catch(Exception ignored){}}
        if(exo==null){Toast.makeText(this,T("subtitle_unavailable"),Toast.LENGTH_SHORT).show();return;}
        try{ArrayList<String> names=new ArrayList<>();ArrayList<TrackPick> picks=new ArrayList<>();names.add(T("off"));picks.add(null);for(Tracks.Group g:exo.getCurrentTracks().getGroups())if(g.getType()==C.TRACK_TYPE_TEXT)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){Format f=g.getTrackFormat(i);names.add(formatTrack(f,T("subtitle_track")+" "+names.size()));picks.add(new TrackPick(g,i));}new AlertDialog.Builder(this).setTitle(T("subtitles")).setItems(names.toArray(new String[0]),(d,w)->{TrackSelectionParameters.Builder b=exo.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT);if(w==0)b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true);else{TrackPick p=picks.get(w);b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false);b.setOverrideForType(new TrackSelectionOverride(p.group.getMediaTrackGroup(),Collections.singletonList(p.index)));}exo.setTrackSelectionParameters(b.build());status.setText(T("subtitles")+": "+names.get(w));}).show();}catch(Exception e){Toast.makeText(this,T("subtitle_unavailable"),Toast.LENGTH_SHORT).show();}
    }

    static class TrackPick{Tracks.Group group;int index;TrackPick(Tracks.Group g,int i){group=g;index=i;}}
    String cleanTrackName(String s,String fallback){if(s==null||s.trim().isEmpty())return fallback;String x=s.trim();String low=x.toLowerCase(Locale.ROOT);for(String code:new String[]{"nl","nld","dut","en","eng","de","deu","ger","fr","fra","fre","es","spa","it","ita","pt","por","tr","tur","pl","pol","ar","ara"})if(low.equals(code)||low.startsWith(code+" ")||low.startsWith(code+"-")){String lang=SettingsStore.displayLanguage(this,code);return lang+(x.length()>code.length()?" · "+x.substring(code.length()).replaceFirst("^[ -]+",""):"");}return x;}
    String formatTrack(Format f,String fallback){ArrayList<String>x=new ArrayList<>();if(f.label!=null&&!f.label.trim().isEmpty())x.add(f.label.trim());if(f.language!=null&&!f.language.trim().isEmpty()&&!"und".equals(f.language))x.add(SettingsStore.displayLanguage(this,f.language));if(f.channelCount>0)x.add(f.channelCount+"ch");return x.isEmpty()?fallback:TextUtils.join(" · ",x);}

    void showSpeedMenu(){
        final float[] rates={0.5f,0.75f,1f,1.25f,1.5f,2f};String[] labels={"0.5×","0.75×","1.0×","1.25×","1.5×","2.0×"};new AlertDialog.Builder(this).setTitle(T("speed_title")).setItems(labels,(d,w)->{playbackSpeed=rates[w];applyPlaybackSpeed();speed.setText(labels[w]);status.setText(T("speed_label")+": "+labels[w]);}).show();
    }
    void applyPlaybackSpeed(){try{if(usingVlc&&vlc!=null)vlc.setRate(playbackSpeed);else if(exo!=null)exo.setPlaybackSpeed(playbackSpeed);}catch(Exception ignored){}}

    void cycleAspect(){aspectMode=(aspectMode+1)%3;applyAspect();aspect.setText(T("aspect")+": "+(aspectMode==0?T("fit"):aspectMode==1?T("fill"):T("zoom")));}
    void applyAspect(){try{if(usingVlc&&vlc!=null){if(aspectMode==0){vlc.setAspectRatio(null);vlc.setScale(0);}else if(aspectMode==1){vlc.setScale(0);vlc.setAspectRatio("16:9");}else{vlc.setAspectRatio(null);vlc.setScale(1.15f);}}else if(media3View!=null){media3View.setResizeMode(aspectMode==0?AspectRatioFrameLayout.RESIZE_MODE_FIT:aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:AspectRatioFrameLayout.RESIZE_MODE_ZOOM);}}catch(Exception ignored){}}

    void showSleepMenu(){String[] items={T("off"),"15 "+T("minutes"),"30 "+T("minutes"),"60 "+T("minutes"),"90 "+T("minutes")};int[] mins={0,15,30,60,90};new AlertDialog.Builder(this).setTitle(T("sleep_timer")).setItems(items,(d,w)->setSleepTimer(mins[w])).show();}
    void setSleepTimer(int minutes){ui.removeCallbacks(sleepStop);if(minutes<=0){sleepUntil=0;sleep.setText(T("sleep_short"));status.setText(T("sleep_off"));return;}sleepUntil=SystemClock.elapsedRealtime()+minutes*60000L;ui.postDelayed(sleepStop,minutes*60000L);sleep.setText(T("sleep_short")+" "+minutes+"m");status.setText(T("sleep_timer")+": "+minutes+" "+T("minutes"));}

    void toggleRecording(){
        if(entry==null||!"live".equals(entry.type)){Toast.makeText(this,T("record_live_only"),Toast.LENGTH_SHORT).show();return;}
        if(recording||recordingStarting){requestStopRecording(null,T("record_stopping"));return;}
        if(!usingVlc||vlc==null){pendingRecordStart=true;status.setText(T("record_switch_vlc"));startVlc();return;}
        startRecording();
    }

    File recordingDirectory(){File base=getExternalFilesDir(Environment.DIRECTORY_MOVIES);if(base==null)base=new File(getFilesDir(),"recordings");File dir=new File(base,"NenoTVRecordings");if(!dir.exists())dir.mkdirs();return dir;}
    long freeBytes(File dir){try{StatFs s=new StatFs(dir.getAbsolutePath());return s.getAvailableBytes();}catch(Exception e){return Long.MAX_VALUE;}}

    void startRecording(){
        if(!currentPlaybackRoute())return;
        if(destroyed||entry==null||!"live".equals(entry.type)||vlc==null)return;
        File dir=recordingDirectory();if(!dir.exists()&&!dir.mkdirs()){status.setText(T("record_dir_failed"));return;}
        if(freeBytes(dir)<200L*1024L*1024L){new AlertDialog.Builder(this).setTitle(T("storage_low_title")).setMessage(T("storage_low_msg")).setPositiveButton("OK",null).show();return;}
        recordingChannel=entry.name==null?T("live_tv"):entry.name;recordingStarting=true;record.setText("REC…");record.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFB91C1C));
        boolean ok=false;try{ok=vlc.record(dir.getAbsolutePath());}catch(Exception ignored){}
        if(!ok){recordingStarting=false;record.setText("● REC");record.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55000000));status.setText(T("record_not_supported"));}
        else status.setText(T("record_starting"));
    }

    void requestStopRecording(Runnable after,String message){
        pendingRecordStart=false;afterRecordStop=after;if(message!=null)status.setText(message);
        if(vlc==null||(!recording&&!recordingStarting)){recording=false;recordingStarting=false;Runnable r=afterRecordStop;afterRecordStop=null;if(r!=null)r.run();return;}
        record.setText("STOP…");boolean ok=false;try{ok=vlc.record(null);}catch(Exception ignored){}
        if(!ok){recording=false;recordingStarting=false;recordingStartedAt=0;record.setText("● REC");record.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55000000));Runnable r=afterRecordStop;afterRecordStop=null;if(r!=null)ui.postDelayed(r,100);}
    }

    void onRecordChanged(boolean active,String path){
        if(playbackRevoked||destroyed)return;
        if(active){recording=true;recordingStarting=false;recordingStartedAt=SystemClock.elapsedRealtime();record.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFB91C1C));status.setText("● "+T("record_active")+" · "+recordingChannel);return;}
        boolean had=recording||recordingStarting;recording=false;recordingStarting=false;recordingStartedAt=0;record.setText("● REC");record.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55000000));
        if(path!=null&&!path.trim().isEmpty()){lastRecordingPath=path;publishRecordingAsync(new File(path),recordingChannel);}else if(had)status.setText(T("record_stopped"));
        Runnable r=afterRecordStop;afterRecordStop=null;if(r!=null)ui.postDelayed(r,250);
    }

    void updateRecordingUi(){if(recording&&recordingStartedAt>0){long ms=SystemClock.elapsedRealtime()-recordingStartedAt;record.setText("● REC "+fmt(ms));}}

    void publishRecordingAsync(File file,String channel){
        exec.execute(()->{String msg=publishRecording(file,channel);runOnUiThread(()->{if(!destroyed)status.setText(msg);});});
    }

    String publishRecording(File file,String channel){
        if(file==null||!file.exists()||file.length()==0)return T("record_missing");
        String ext=extension(file.getName());String display="SunnyIPTV_"+safeFile(channel)+"_"+new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.ROOT).format(new Date())+ext;String mime=mimeFor(ext);
        if(Build.VERSION.SDK_INT>=29){
            ContentResolver cr=getContentResolver();ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,display);v.put(MediaStore.Video.Media.MIME_TYPE,mime);v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/SunnyIPTV");v.put(MediaStore.Video.Media.IS_PENDING,1);Uri out=null;
            try{out=cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(out==null)throw new IOException("MediaStore insert failed");try(InputStream in=new FileInputStream(file);OutputStream os=cr.openOutputStream(out)){if(os==null)throw new IOException("Output stream ontbreekt");byte[] buf=new byte[1024*1024];int n;while((n=in.read(buf))>0)os.write(buf,0,n);}ContentValues done=new ContentValues();done.put(MediaStore.Video.Media.IS_PENDING,0);cr.update(out,done,null,null);file.delete();return T("record_saved")+" · Films/SunnyIPTV/"+display;}catch(Exception e){if(out!=null)try{cr.delete(out,null,null);}catch(Exception ignored){}return T("record_saved_app")+" · "+file.getAbsolutePath();}
        }
        File named=new File(recordingDirectory(),display);try{if(!file.equals(named)){if(!file.renameTo(named)){try(InputStream in=new FileInputStream(file);OutputStream os=new FileOutputStream(named)){byte[] buf=new byte[1024*1024];int n;while((n=in.read(buf))>0)os.write(buf,0,n);}file.delete();}}return T("record_saved")+" · "+named.getAbsolutePath();}catch(Exception e){return T("record_saved")+" · "+file.getAbsolutePath();}
    }

    String extension(String name){if(name==null)return ".ts";int i=name.lastIndexOf('.');if(i<0||i<name.length()-6)return ".ts";return name.substring(i).toLowerCase(Locale.ROOT);}
    String mimeFor(String ext){if(".mp4".equals(ext)||".m4v".equals(ext))return "video/mp4";if(".mkv".equals(ext))return "video/x-matroska";if(".ts".equals(ext)||".mts".equals(ext)||".m2ts".equals(ext))return "video/mp2t";return "video/*";}
    String safeFile(String x){String s=x==null?"Live":x.replaceAll("[\\/:*?\"<>|]+","_").replaceAll("\\s+"," ").trim();return s.isEmpty()?"Live":s.substring(0,Math.min(48,s.length()));}

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
    void attachSubtitle(File f,String label){if(destroyed)return;if(usingVlc&&vlc!=null){try{boolean ok=vlc.addSlave(Media.Slave.Type.Subtitle,Uri.fromFile(f),true);status.setText(ok?T("subtitles")+": "+label:T("subtitle_downloaded"));}catch(Exception e){status.setText(T("subtitle_ready"));}}else if(exo!=null){long pos=exo.getCurrentPosition();boolean was=exo.isPlaying();playMedia3Candidate(pos);if(was)exo.play();status.setText(T("subtitles")+": "+label);}}
    void releaseVlc(){if(vlc!=null){try{vlc.stop();vlc.detachViews();vlc.release();}catch(Exception ignored){}vlc=null;}if(libVLC!=null){try{libVLC.release();}catch(Exception ignored){}libVLC=null;}}
    void releasePlayers(){releaseVlc();if(exo!=null){try{exo.release();}catch(Exception ignored){}exo=null;}}
    void saveProgress(){if(library!=null&&entry!=null)library.saveProgress(entry,currentPosition(),duration(),true);}

    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        if("live".equals(entry.type)&&liveQueue.size()>1){if(keyCode==KeyEvent.KEYCODE_CHANNEL_UP||keyCode==KeyEvent.KEYCODE_DPAD_UP){switchLive(1);showControls();return true;}if(keyCode==KeyEvent.KEYCODE_CHANNEL_DOWN||keyCode==KeyEvent.KEYCODE_DPAD_DOWN){switchLive(-1);showControls();return true;}}
        if(keyCode==KeyEvent.KEYCODE_MEDIA_RECORD&&"live".equals(entry.type)){toggleRecording();showControls();return true;}
        if(keyCode==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||keyCode==KeyEvent.KEYCODE_SPACE){togglePlay();showControls();return true;}
        if(!"live".equals(entry.type)&&(keyCode==KeyEvent.KEYCODE_MEDIA_REWIND||keyCode==KeyEvent.KEYCODE_DPAD_LEFT)){seekBy(-10000);showControls();return true;}
        if(!"live".equals(entry.type)&&(keyCode==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)){seekBy(10000);showControls();return true;}
        return super.onKeyDown(keyCode,event);
    }

    @Override public void onUserLeaveHint(){super.onUserLeaveHint();enterPip();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    @Override protected void onStart(){super.onStart();setupCast();if(!com.nenotv.player.storage.ExtraPrivacyStore.allowsSdk(this)){if(casting)disconnectCastSession(true);return;}try{if(castContext==null)castContext=CastContext.getSharedInstance(this);if(castContext!=null){castContext.getSessionManager().addSessionManagerListener(castSessionListener,CastSession.class);CastSession c=castContext.getSessionManager().getCurrentCastSession();if(c!=null&&c.isConnected()&&!casting)connectCastSession(c,true);}}catch(Throwable ignored){}}
    @Override protected void onStop(){if(castContext!=null)try{castContext.getSessionManager().removeSessionManagerListener(castSessionListener,CastSession.class);}catch(Exception ignored){}super.onStop();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)ScreenInsets.player(this);}
    @Override protected void onPause(){saveProgress();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;ui.removeCallbacksAndMessages(null);saveProgress();try{if(castClient!=null)castClient.unregisterCallback(castMediaCallback);}catch(Exception ignored){}closeCastRelay();if(vlc!=null&&(recording||recordingStarting))try{vlc.record(null);}catch(Exception ignored){}releasePlayers();exec.shutdownNow();super.onDestroy();}
}

