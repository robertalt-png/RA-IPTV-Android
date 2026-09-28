from pathlib import Path
import re

root=Path('.')
app=root/'app'

gradle=app/'build.gradle'
s=gradle.read_text()
s=s.replace("applicationId 'com.robertalt.raiptv'","applicationId 'com.robertalt.raiptv.light'")
s=re.sub(r"versionCode\\s+\\d+","versionCode 1",s,1)
s=re.sub(r"versionName\\s+'[^']+'","versionName '0.11.0-light'",s,1)
s=s.replace("    buildFeatures { buildConfig true }","    buildFeatures { buildConfig true }\n    dynamicFeatures = [':proextras']")
s=s.replace("        buildConfigField 'String', 'NIVARO_CAST_RECEIVER_ID', '\"' + nivaroCastReceiverId + '\"'","        buildConfigField 'String', 'NIVARO_CAST_RECEIVER_ID', '\"' + nivaroCastReceiverId + '\"'\n        buildConfigField 'boolean', 'LIGHT_BUILD', 'true'")
s=s.replace("    implementation 'org.videolan.android:libvlc-all:3.7.6'\n","")
s=s.replace("    implementation 'com.google.mlkit:translate:17.0.3'\n","")
s=s.replace("    implementation 'com.google.mlkit:language-id:17.0.6'\n","")
if "androidx.appcompat:appcompat" not in s:
    s=s.replace("dependencies {","dependencies {\n    implementation 'androidx.appcompat:appcompat:1.7.1'")
if "feature-delivery" not in s:
    s=s.replace("dependencies {","dependencies {\n    implementation 'com.google.android.play:feature-delivery:2.1.0'")
gradle.write_text(s)

settings=root/'settings.gradle'
x=settings.read_text()
if "':proextras'" not in x:
    x += "\ninclude ':proextras'\n"
settings.write_text(x)

strings=app/'src/main/res/values/strings.xml'
x=strings.read_text()
x=re.sub(r'<string name="app_name">.*?</string>','<string name="app_name">NenoTV Light</string>',x)
if 'name="title_proextras"' not in x:
    x=x.replace('</resources>','    <string name="title_proextras">NenoTV Pro Media Pack</string>\n</resources>')
strings.write_text(x)
feature_strings=app/'src/main/res/values/feature_strings.xml'
feature_strings.write_text('<resources><string name="title_proextras">NenoTV Pro Media Pack</string></resources>\n')

layout=app/'src/main/res/layout/activity_player.xml'
x=layout.read_text()
x=x.replace('<org.videolan.libvlc.util.VLCVideoLayout android:id="@+id/vlcLayout" android:layout_width="match_parent" android:layout_height="match_parent" />','<FrameLayout android:id="@+id/vlcLayout" android:layout_width="1dp" android:layout_height="1dp" android:visibility="gone" />')
x=x.replace('android:id="@+id/media3View" android:layout_width="match_parent" android:layout_height="match_parent" android:visibility="gone"','android:id="@+id/media3View" android:layout_width="match_parent" android:layout_height="match_parent" android:visibility="visible"')
layout.write_text(x)

translator=app/'src/main/java/com/robertalt/raiptv/InfoTranslator.java'
translator.write_text("""package com.robertalt.raiptv;

public final class InfoTranslator {
    public interface Callback { void done(String text); }
    private InfoTranslator(){}
    public static void translate(String text,String target,Callback cb){ if(cb!=null)cb.done(text==null?"":text); }
}
""")

player=app/'src/main/java/com/robertalt/raiptv/PlayerActivity.java'
player.write_text(r'''package com.robertalt.raiptv;

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
import com.robertalt.raiptv.model.MediaEntry;
import com.robertalt.raiptv.storage.CrashGuard;
import com.robertalt.raiptv.storage.LibraryStore;
import com.robertalt.raiptv.storage.SettingsStore;
import java.util.*;

public class PlayerActivity extends FragmentActivity {
    PlayerView media3View; ExoPlayer exo; MediaEntry entry; LibraryStore library;
    TextView title,status,timeText; Button playPause,rewind,forward,audio,subtitle,pip,speed,aspect,sleep,record,favorite,castButton,channelPrev,channelNext;
    SeekBar seek; FrameLayout controls; Handler ui=new Handler(Looper.getMainLooper()); boolean userSeeking=false,destroyed=false; int aspectMode=0; float playbackSpeed=1f;
    String T(String k){return UiText.t(this,k);}
    Runnable tick=new Runnable(){public void run(){if(destroyed)return;updateProgress();ui.postDelayed(this,500);}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);CrashGuard.install(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(Color.BLACK);
        setContentView(R.layout.activity_player);UiText.applyDirection(this);library=new LibraryStore(this);
        media3View=findViewById(R.id.media3View);controls=findViewById(R.id.playerControls);title=findViewById(R.id.playerTitle);status=findViewById(R.id.playerStatus);timeText=findViewById(R.id.timeText);playPause=findViewById(R.id.playPauseButton);rewind=findViewById(R.id.rewindButton);forward=findViewById(R.id.forwardButton);audio=findViewById(R.id.audioButton);subtitle=findViewById(R.id.subtitleButton);pip=findViewById(R.id.pipButton);seek=findViewById(R.id.seekBar);speed=findViewById(R.id.speedButton);aspect=findViewById(R.id.aspectButton);sleep=findViewById(R.id.sleepButton);record=findViewById(R.id.recordButton);favorite=findViewById(R.id.favoriteButton);castButton=findViewById(R.id.castRouteButton);channelPrev=findViewById(R.id.channelPrevButton);channelNext=findViewById(R.id.channelNextButton);
        entry=(MediaEntry)getIntent().getSerializableExtra("media");if(entry==null){finish();return;}title.setText(DisplayText.title(entry));
        record.setVisibility(View.GONE);castButton.setVisibility(View.GONE);channelPrev.setVisibility(View.GONE);channelNext.setVisibility(View.GONE);pip.setVisibility(View.GONE);
        wire();startPlayer();ui.post(tick);
    }

    void wire(){
        playPause.setOnClickListener(v->{if(exo==null)return;if(exo.isPlaying())exo.pause();else exo.play();updatePlayIcon();});
        rewind.setOnClickListener(v->{if(exo!=null)exo.seekTo(Math.max(0,exo.getCurrentPosition()-10000));});
        forward.setOnClickListener(v->{if(exo!=null)exo.seekTo(exo.getCurrentPosition()+10000);});
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){userSeeking=true;}public void onStopTrackingTouch(SeekBar b){userSeeking=false;if(exo!=null&&exo.getDuration()>0)exo.seekTo((long)(exo.getDuration()*(b.getProgress()/1000f)));}public void onProgressChanged(SeekBar b,int p,boolean u){}});
        favorite.setOnClickListener(v->{if(entry!=null){library.toggleFavorite(entry);updateFavorite();}});
        audio.setOnClickListener(v->showTracks(C.TRACK_TYPE_AUDIO));subtitle.setOnClickListener(v->showTracks(C.TRACK_TYPE_TEXT));speed.setOnClickListener(v->showSpeed());aspect.setOnClickListener(v->cycleAspect());sleep.setVisibility(View.GONE);updateFavorite();
    }

    void startPlayer(){
        ArrayList<String> urls=new ArrayList<>(entry.candidates);if(urls.isEmpty()&&entry.url!=null&&!entry.url.isEmpty())urls.add(entry.url);if(urls.isEmpty()){status.setText(T("no_stream_url"));return;}
        exo=new ExoPlayer.Builder(this).build();media3View.setPlayer(exo);exo.setMediaItem(MediaItem.fromUri(urls.get(0)));long resume=library.progress(entry);exo.prepare();if(resume>10000&&!"live".equals(entry.type))exo.seekTo(resume);exo.play();status.setText("Media3 · "+T("playing"));
        exo.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY)status.setText("Media3 · "+T("playing"));else if(state==Player.STATE_ENDED)finish();}@Override public void onPlayerError(PlaybackException e){status.setText(T("error_prefix")+": "+e.getErrorCodeName());}});
        updatePlayIcon();
    }

    void updateProgress(){if(exo==null)return;long pos=Math.max(0,exo.getCurrentPosition()),dur=Math.max(0,exo.getDuration());if(!userSeeking&&dur>0)seek.setProgress((int)Math.min(1000,pos*1000/dur));timeText.setText(fmt(pos)+(dur>0?" / "+fmt(dur):""));if(entry!=null&&!"live".equals(entry.type)&&pos>5000)library.saveProgress(entry,pos,dur);updatePlayIcon();}
    String fmt(long ms){long s=Math.max(0,ms/1000),m=s/60,h=m/60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m%60,s%60):String.format(Locale.ROOT,"%02d:%02d",m,s%60);}
    void updatePlayIcon(){if(playPause!=null)playPause.setText(exo!=null&&exo.isPlaying()?"❚❚":"▶");}
    void updateFavorite(){if(favorite!=null&&entry!=null)favorite.setText(library.isFavorite(entry)?"♥":"♡");}
    void showSpeed(){final float[] r={.75f,1f,1.25f,1.5f,2f};String[] l={"0.75×","1.0×","1.25×","1.5×","2.0×"};new AlertDialog.Builder(this).setTitle(T("speed_title")).setItems(l,(d,w)->{playbackSpeed=r[w];if(exo!=null)exo.setPlaybackSpeed(playbackSpeed);speed.setText(l[w]);}).show();}
    void cycleAspect(){aspectMode=(aspectMode+1)%3;media3View.setResizeMode(aspectMode==0?AspectRatioFrameLayout.RESIZE_MODE_FIT:aspectMode==1?AspectRatioFrameLayout.RESIZE_MODE_FILL:AspectRatioFrameLayout.RESIZE_MODE_ZOOM);}
    void showTracks(int type){if(exo==null)return;Tracks tr=exo.getCurrentTracks();ArrayList<String> names=new ArrayList<>();ArrayList<TrackSelectionOverride> picks=new ArrayList<>();for(Tracks.Group g:tr.getGroups()){if(g.getType()!=type)continue;for(int i=0;i<g.length;i++){Format f=g.getTrackFormat(i);String n=f.label!=null?f.label:(f.language!=null?SettingsStore.displayLanguage(this,f.language):T(type==C.TRACK_TYPE_AUDIO?"audio":"subtitles"));names.add(n);picks.add(new TrackSelectionOverride(g.getMediaTrackGroup(),Collections.singletonList(i)));}}if(names.isEmpty()){Toast.makeText(this,type==C.TRACK_TYPE_AUDIO?T("no_audio_tracks"):T("no_subtitles"),Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(type==C.TRACK_TYPE_AUDIO?T("audio_track"):T("subtitles")).setItems(names.toArray(new String[0]),(d,w)->{TrackSelectionParameters.Builder pb=exo.getTrackSelectionParameters().buildUpon();pb.setOverrideForType(picks.get(w));exo.setTrackSelectionParameters(pb.build());}).show();}
    @Override protected void onStop(){super.onStop();if(entry!=null&&exo!=null&&!"live".equals(entry.type))library.saveProgress(entry,Math.max(0,exo.getCurrentPosition()),Math.max(0,exo.getDuration()));}
    @Override protected void onDestroy(){destroyed=true;ui.removeCallbacksAndMessages(null);if(exo!=null){exo.release();exo=null;}super.onDestroy();}
}
''')

installer=app/'src/main/java/com/robertalt/raiptv/ProModuleInstaller.java'
installer.write_text("""package com.robertalt.raiptv;

import android.app.Activity;
import android.widget.Toast;
import com.google.android.play.core.splitinstall.*;
public final class ProModuleInstaller {
  private static final String MODULE="proextras";
  private ProModuleInstaller(){}
  public static boolean isInstalled(Activity a){try{return SplitInstallManagerFactory.create(a).getInstalledModules().contains(MODULE);}catch(Throwable t){return false;}}
  public static void request(Activity a){
    try{
      SplitInstallManager m=SplitInstallManagerFactory.create(a);if(m.getInstalledModules().contains(MODULE))return;
      SplitInstallRequest r=SplitInstallRequest.newBuilder().addModule(MODULE).build();
      m.startInstall(r).addOnSuccessListener(id->Toast.makeText(a,"NenoTV Pro-module wordt gedownload…",Toast.LENGTH_LONG).show()).addOnFailureListener(e->Toast.makeText(a,"Pro-module is beschikbaar via Google Play.",Toast.LENGTH_LONG).show());
    }catch(Throwable t){Toast.makeText(a,"Pro-module is beschikbaar via Google Play.",Toast.LENGTH_LONG).show();}
  }
}
""")

pg=app/'src/main/java/com/robertalt/raiptv/ProGate.java'
x=pg.read_text()
x=x.replace('if(allowed(a))return true;','if(allowed(a)){ if(!ProModuleInstaller.isInstalled(a)){ProModuleInstaller.request(a);return false;} return true; }')
pg.write_text(x)

mod=root/'proextras'
(mod/'src/main/java/com/robertalt/raiptv/proextras').mkdir(parents=True,exist_ok=True)
(mod/'src/main/res/values').mkdir(parents=True,exist_ok=True)
(mod/'build.gradle').write_text("""plugins { id 'com.android.dynamic-feature' }

android {
    namespace 'com.robertalt.raiptv.proextras'
    compileSdk 36
    defaultConfig { minSdk 26 }
    compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
}

dependencies {
    implementation project(':app')
    implementation 'org.videolan.android:libvlc-all:3.7.6'
    implementation 'com.google.mlkit:translate:17.0.3'
    implementation 'com.google.mlkit:language-id:17.0.6'
}
""")
(mod/'src/main/AndroidManifest.xml').write_text("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:dist="http://schemas.android.com/apk/distribution">
  <dist:module dist:instant="false" dist:title="@string/title_proextras">
    <dist:delivery><dist:on-demand/></dist:delivery>
    <dist:fusing dist:include="true"/>
  </dist:module>
  <application/>
</manifest>
""")
(mod/'src/main/res/values/strings.xml').write_text('<resources><string name="title_proextras">NenoTV Pro Media Pack</string></resources>\n')
(mod/'src/main/java/com/robertalt/raiptv/proextras/ProExtrasMarker.java').write_text('package com.robertalt.raiptv.proextras; public final class ProExtrasMarker { private ProExtrasMarker(){} public static String name(){return "NenoTV Pro Media Pack";} }\n')
print('Prepared NenoTV Light + on-demand proextras module')
