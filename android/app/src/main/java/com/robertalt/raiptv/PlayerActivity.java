package com.nenotv.player;

import android.app.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import com.nenotv.player.model.MediaEntry;
import com.nenotv.player.storage.CrashGuard;
import com.nenotv.player.storage.LibraryStore;
import com.nenotv.player.storage.SettingsStore;
import java.util.*;

public class PlayerActivity extends FragmentActivity {
    PlayerView media3View; ExoPlayer exo; MediaEntry entry; LibraryStore library;
    TextView title,status,timeText; Button playPause,rewind,forward,audio,subtitle,pip,speed,aspect,sleep,record,favorite,castButton,channelPrev,channelNext;
    SeekBar seek; FrameLayout controls; Handler ui=new Handler(Looper.getMainLooper()); boolean userSeeking=false,destroyed=false; int aspectMode=0; float playbackSpeed=1f;
    String T(String k){return UiText.t(this,k);}
    Runnable tick=new Runnable(){public void run(){if(destroyed)return;updateProgress();ui.postDelayed(this,500);}};
    Runnable hideControlsTask=()->hideControls();

    void cancelControlsHide(){ui.removeCallbacks(hideControlsTask);}
    void scheduleControlsHide(){
        cancelControlsHide();
        if(exo!=null&&exo.isPlaying())ui.postDelayed(hideControlsTask,3000);
    }
    void showControls(){
        cancelControlsHide();
        if(controls==null)return;
        controls.animate().cancel();controls.setAlpha(1f);controls.setVisibility(View.VISIBLE);
        scheduleControlsHide();
    }
    void hideControls(){
        cancelControlsHide();
        if(controls==null||controls.getVisibility()!=View.VISIBLE)return;
        controls.animate().cancel();
        controls.animate().alpha(0f).setDuration(180).withEndAction(()->{
            if(controls!=null){controls.setVisibility(View.GONE);controls.setAlpha(1f);}
        }).start();
    }
    void touchControls(){
        if(controls==null)return;
        if(controls.getVisibility()==View.VISIBLE)hideControls();else showControls();
    }

    private final android.os.Handler demoHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable demoExpiryCheck=new Runnable(){public void run(){
        if(isFinishing()||isDestroyed())return;
        if(DemoPolicy.expired(PlayerActivity.this)){finish();return;}
        demoHandler.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle b){
        super.onCreate(b);if(DemoPolicy.blockPlayback(this)){finish();return;}com.nenotv.player.storage.SecureProfileStore demoStore=new com.nenotv.player.storage.SecureProfileStore(this);if(demoStore.exists()&&DemoPolicy.isDemo(demoStore.load()))demoHandler.post(demoExpiryCheck);CrashGuard.install(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(Color.BLACK);
        setContentView(R.layout.activity_player);UiText.applyDirection(this);library=new LibraryStore(this);
        media3View=findViewById(R.id.media3View);controls=findViewById(R.id.playerControls);title=findViewById(R.id.playerTitle);status=findViewById(R.id.playerStatus);timeText=findViewById(R.id.timeText);playPause=findViewById(R.id.playPauseButton);rewind=findViewById(R.id.rewindButton);forward=findViewById(R.id.forwardButton);audio=findViewById(R.id.audioButton);subtitle=findViewById(R.id.subtitleButton);pip=findViewById(R.id.pipButton);seek=findViewById(R.id.seekBar);speed=findViewById(R.id.speedButton);aspect=findViewById(R.id.aspectButton);sleep=findViewById(R.id.sleepButton);record=findViewById(R.id.recordButton);favorite=findViewById(R.id.favoriteButton);castButton=findViewById(R.id.castRouteButton);channelPrev=findViewById(R.id.channelPrevButton);channelNext=findViewById(R.id.channelNextButton);
        entry=(MediaEntry)getIntent().getSerializableExtra("media");if(entry==null){finish();return;}title.setText(DisplayText.title(entry));
        record.setVisibility(View.GONE);castButton.setVisibility(View.GONE);channelPrev.setVisibility(View.GONE);channelNext.setVisibility(View.GONE);pip.setVisibility(View.GONE);
        media3View.setUseController(false);
        media3View.setOnClickListener(v->touchControls());
        controls.setOnClickListener(v->touchControls());
        ScreenInsets.player(this);rewind.setText("−10 s");forward.setText("+10 s");rewind.setContentDescription("−10 "+T("seconds"));forward.setContentDescription("+10 "+T("seconds"));wire();startPlayer();ui.post(tick);showControls();
    }

    void wire(){
        playPause.setOnClickListener(v->{if(exo==null)return;if(exo.isPlaying()){exo.pause();cancelControlsHide();showControls();}else{exo.play();showControls();}updatePlayIcon();});
        rewind.setOnClickListener(v->{if(exo!=null)exo.seekTo(Math.max(0,exo.getCurrentPosition()-10000));showControls();});
        forward.setOnClickListener(v->{if(exo!=null)exo.seekTo(exo.getDuration()>0?Math.min(exo.getDuration(),exo.getCurrentPosition()+10000):exo.getCurrentPosition()+10000);showControls();});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){userSeeking=true;cancelControlsHide();}public void onStopTrackingTouch(SeekBar b){userSeeking=false;if(exo!=null&&exo.getDuration()>0)exo.seekTo((long)(exo.getDuration()*(b.getProgress()/1000f)));showControls();}public void onProgressChanged(SeekBar b,int p,boolean u){}});
        favorite.setOnClickListener(v->{if(entry!=null){library.toggleFavorite(entry);updateFavorite();}showControls();});
        audio.setOnClickListener(v->{cancelControlsHide();showTracks(C.TRACK_TYPE_AUDIO);});subtitle.setOnClickListener(v->{cancelControlsHide();showTracks(C.TRACK_TYPE_TEXT);});speed.setOnClickListener(v->{cancelControlsHide();showSpeed();});aspect.setOnClickListener(v->{cycleAspect();showControls();});sleep.setVisibility(View.GONE);updateFavorite();
    }

    void startPlayer(){
        ArrayList<String> urls=new ArrayList<>(entry.candidates);if(urls.isEmpty()&&entry.url!=null&&!entry.url.isEmpty())urls.add(entry.url);if(urls.isEmpty()){status.setText(T("no_stream_url"));return;}
        exo=new ExoPlayer.Builder(this).setMediaSourceFactory(DemoSource.mediaSourceFactory(this,entry)).build();media3View.setPlayer(exo);exo.setMediaItem(MediaItem.fromUri(urls.get(0)));long resume=library.progress(entry);exo.prepare();if(resume>10000&&!"live".equals(entry.type))exo.seekTo(resume);exo.play();status.setText("Media3 · "+T("playing"));
        exo.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){status.setText("Media3 · "+T("playing"));scheduleControlsHide();}else if(state==Player.STATE_ENDED){library.markWatched(entry);finish();}}@Override public void onIsPlayingChanged(boolean playing){if(playing)scheduleControlsHide();else{cancelControlsHide();showControls();}}@Override public void onPlayerError(PlaybackException e){cancelControlsHide();showControls();status.setText(T("error_prefix")+": "+e.getErrorCodeName());}});
        updatePlayIcon();
    }

    void updateProgress(){if(exo==null)return;long pos=Math.max(0,exo.getCurrentPosition()),dur=Math.max(0,exo.getDuration());if(!userSeeking&&dur>0)seek.setProgress((int)Math.min(1000,pos*1000/dur));timeText.setText(fmt(pos)+(dur>0?" / "+fmt(dur):""));if(entry!=null&&!"live".equals(entry.type)&&pos>5000)library.saveProgress(entry,pos,dur);updatePlayIcon();}
    String fmt(long ms){long s=Math.max(0,ms/1000),m=s/60,h=m/60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m%60,s%60):String.format(Locale.ROOT,"%02d:%02d",m,s%60);}
    void updatePlayIcon(){if(playPause!=null)playPause.setText(exo!=null&&exo.isPlaying()?"❚❚":"▶");}
    void updateFavorite(){if(favorite!=null&&entry!=null)favorite.setText(library.isFavorite(entry)?"♥":"♡");}
    void showSpeed(){final float[] r={.75f,1f,1.25f,1.5f,2f};String[] l={"0.75×","1.0×","1.25×","1.5×","2.0×"};new AlertDialog.Builder(this).setTitle(T("speed_title")).setItems(l,(d,w)->{playbackSpeed=r[w];if(exo!=null)exo.setPlaybackSpeed(playbackSpeed);speed.setText(l[w]);}).show();}
    void cycleAspect(){aspectMode=(aspectMode+1)%3;media3View.setResizeMode(aspectMode==0?AspectRatioFrameLayout.RESIZE_MODE_FIT:aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:AspectRatioFrameLayout.RESIZE_MODE_ZOOM);}
    void showTracks(int type){if(exo==null)return;Tracks tr=exo.getCurrentTracks();ArrayList<String> names=new ArrayList<>();ArrayList<TrackSelectionOverride> picks=new ArrayList<>();for(Tracks.Group g:tr.getGroups()){if(g.getType()!=type)continue;for(int i=0;i<g.length;i++){Format f=g.getTrackFormat(i);String n=f.label!=null?f.label:(f.language!=null?SettingsStore.displayLanguage(this,f.language):T(type==C.TRACK_TYPE_AUDIO?"audio":"subtitles"));names.add(n);picks.add(new TrackSelectionOverride(g.getMediaTrackGroup(),Collections.singletonList(i)));}}if(names.isEmpty()){Toast.makeText(this,type==C.TRACK_TYPE_AUDIO?T("no_audio_tracks"):T("no_subtitles"),Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(type==C.TRACK_TYPE_AUDIO?T("audio_track"):T("subtitles")).setItems(names.toArray(new String[0]),(d,w)->{TrackSelectionParameters.Builder pb=exo.getTrackSelectionParameters().buildUpon();pb.setOverrideForType(picks.get(w));exo.setTrackSelectionParameters(pb.build());}).show();}
    @Override protected void onStop(){super.onStop();if(entry!=null&&exo!=null&&!"live".equals(entry.type))library.saveProgress(entry,Math.max(0,exo.getCurrentPosition()),Math.max(0,exo.getDuration()),true);}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)ScreenInsets.player(this);}
    @Override protected void onDestroy(){destroyed=true;ui.removeCallbacksAndMessages(null);if(exo!=null){exo.release();exo=null;}super.onDestroy();}
}
