package com.nenotv.player;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import com.nenotv.player.model.*;
import com.nenotv.player.storage.*;
import com.nenotv.player.provider.*;
import java.util.*;
import java.io.*;
import java.net.*;

/** Exercise user-visible behaviour on the actual universal APK generated from the Play AAB. */
public final class UiInstrumentation extends ImportInstrumentation {
    Bundle args;
    @Override public void runOnMainSync(Runnable action){java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();super.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure.set(e);}});Throwable e=failure.get();if(e instanceof Error)throw (Error)e;if(e!=null)throw new RuntimeException(e);}

    @Override public void onCreate(Bundle b){args=b==null?new Bundle():b;super.onCreate(b);}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static MediaEntry episode(int season,int episode){MediaEntry e=new MediaEntry();e.id="ui-s"+season+"-e"+episode;e.name="QA series · S"+season+"E"+episode;e.type="episode";e.season=season;e.episode=episode;e.seriesId="ui-series";return e;}
    static EpgEntry programme(String title,long start,long end){EpgEntry e=new EpgEntry();e.title=title;e.startEpoch=start;e.endEpoch=end;return e;}
    static void assertUnclippedText(TextView view){
        check(view!=null&&view.getLayout()!=null,"Favorite button was not laid out");
        android.text.Layout text=view.getLayout();int lines=text.getLineCount();
        check(lines>0&&text.getLineBottom(lines-1)<=view.getHeight()-view.getCompoundPaddingTop()-view.getCompoundPaddingBottom(),"Favorite label clipped vertically");
        for(int i=0;i<lines;i++)check(text.getEllipsisCount(i)==0&&text.getLineWidth(i)<=view.getWidth()-view.getCompoundPaddingLeft()-view.getCompoundPaddingRight()+1,"Favorite label clipped horizontally");
    }
    void snapshot(String name)throws Exception{
        Thread.sleep(350);Bitmap bitmap=getUiAutomation().takeScreenshot();check(bitmap!=null,"Screenshot missing: "+name);
        File folder=new File(getTargetContext().getExternalFilesDir(null),"qa");folder.mkdirs();try(FileOutputStream f=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,f);}bitmap.recycle();
    }
    void xtreamSharedDownloads()throws Exception{
        Context c=getTargetContext();
        SourceRegistryChecks.run(c);
        String previousStart=SettingsStore.startScreen(c);
        try{
            for(String category:new String[]{"NL | Films","General"})try(XtreamFixture fixture=new XtreamFixture(category)){
                Profile p=new Profile();p.type=Profile.Type.XTREAM;p.server=fixture.url();p.username="qa-"+System.nanoTime();p.password="qa";p.name="Xtream QA";
                new SecureProfileStore(c).save(p);
                SettingsStore.setPrimaryLanguage(c,"nl");
                SettingsStore.prefs(c).edit().putString("start_screen","vod").commit();
                c.getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE).edit().putString("level","FREE").commit();
                MainActivity a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try{
                    waitForIdleSync();
                    long end=SystemClock.elapsedRealtime()+30000;
                    while(SystemClock.elapsedRealtime()<end){
                        if(a.profile!=null&&a.searchIndex.isComplete(a.profileKey(),"vod")&&a.searchIndex.isComplete(a.profileKey(),"live")&&a.searchIndex.isComplete(a.profileKey(),"series")&&!a.indexRefreshRunning)break;
                        Thread.sleep(100);
                    }
                    check(a.profile!=null&&a.searchIndex.isComplete(a.profileKey(),"vod")&&a.searchIndex.isComplete(a.profileKey(),"series"),"Shared Xtream import did not complete");
                    Thread.sleep(1700);
                    for(String action:new String[]{"get_live_streams","get_vod_streams","get_series"})check(fixture.count(action)==1,"Duplicate Xtream download: "+action+"="+fixture.count(action));
                    for(String action:new String[]{"get_live_categories","get_vod_categories","get_series_categories"})check(fixture.count(action)==1,"Duplicate category download: "+action+"="+fixture.count(action));
                    java.util.concurrent.atomic.AtomicBoolean populated=new java.util.concurrent.atomic.AtomicBoolean();
                    long visibleDeadline=SystemClock.elapsedRealtime()+5000;
                    while(!populated.get()&&SystemClock.elapsedRealtime()<visibleDeadline){runOnMainSync(()->populated.set(a.gridAdapter.getCount()==161));Thread.sleep(100);}
                    if(!populated.get())snapshot("xtream-failed");
                    runOnMainSync(()->check(populated.get(),"Shared import screen: category="+category+", section="+a.section+", group="+a.currentCategoryId+", cards="+a.gridAdapter.getCount()+", cached="+a.searchIndex.countSection(a.profileKey(),"vod")+", language="+a.searchIndex.countLanguage(a.profileKey(),"vod","nl")+", status="+a.status.getText()));
                    snapshot("xtream-shared-"+(category.startsWith("NL")?"language":"all"));
                }finally{runOnMainSync(a::finish);waitForIdleSync();}
            }
        }finally{SettingsStore.prefs(c).edit().putString("start_screen",previousStart).commit();new SecureProfileStore(c).clear();}
    }
    static final class XtreamFixture implements AutoCloseable{
        final ServerSocket socket;final Thread worker;final String category;
        final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger> counts=new java.util.concurrent.ConcurrentHashMap<>();
        XtreamFixture(String category)throws Exception{
            this.category=category;socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            worker=new Thread(()->{while(!socket.isClosed())try(Socket connection=socket.accept()){
                BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream()));String request=reader.readLine(),line;
                while((line=reader.readLine())!=null&&!line.isEmpty()){}
                String action="";
                if(request!=null){String query=request.split(" ")[1];int start=query.indexOf("action=");if(start>=0){action=query.substring(start+7);int amp=action.indexOf('&');if(amp>=0)action=action.substring(0,amp);}}
                counts.computeIfAbsent(action,k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                String json="{}";
                if(action.isEmpty())json="{\"user_info\":{\"auth\":1}}";
                else if(action.endsWith("_categories"))json="[{\"category_id\":\"1\",\"category_name\":\""+category+"\"}]";
                else if(action.equals("get_live_streams")||action.equals("get_vod_streams")||action.equals("get_series")){
                    StringBuilder rows=new StringBuilder("[");for(int i=0;i<161;i++){if(i>0)rows.append(',');rows.append("{\"stream_id\":").append(i+1).append(",\"series_id\":").append(i+1).append(",\"name\":\"QA ").append(i).append("\",\"category_id\":\"1\"}");}json=rows.append(']').toString();
                }
                byte[] body=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);OutputStream response=connection.getOutputStream();
                response.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));response.write(body);response.flush();
            }catch(Exception ignored){}},"xtream-fixture");worker.setDaemon(true);worker.start();
        }
        String url(){return "http://127.0.0.1:"+socket.getLocalPort();}
        int count(String action){java.util.concurrent.atomic.AtomicInteger n=counts.get(action);return n==null?0:n.get();}
        public void close()throws Exception{socket.close();worker.join(1000);}
    }
    void onboarding(Bundle result)throws Exception{
        Context c=getTargetContext();
        SecureProfileStore profiles=new SecureProfileStore(c);
        profiles.clear();
        SettingsStore.prefs(c).edit().remove("demo_consumed").remove("demo_expires_at").remove("demo_started_at").commit();
        for(String language:new String[]{"nl","en","de"}){
            SettingsStore.setPrimaryLanguage(c,language);
            ProfileActivity a=(ProfileActivity)startActivitySync(new Intent(c,ProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(()->{
                check(a.demoRadio.isEnabled()&&a.demoRadio.isChecked(),"NenoTV offer not selected on fresh install");
                if(Build.VERSION.SDK_INT>=30){
                    View heading=a.findViewById(R.id.profileTitle);int[] position=new int[2];heading.getLocationOnScreen(position);
                    WindowInsets insets=heading.getRootWindowInsets();
                    check(insets!=null&&position[1]>=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()).top,"Onboarding title overlaps system bars");
                }
                check(a.xtreamFields.getVisibility()==View.GONE&&a.m3uFields.getVisibility()==View.GONE,"Technical fields shown before source selection");
                check(a.findViewById(R.id.nenoOffer).getVisibility()==View.VISIBLE,"NenoTV catalogue missing");
                check(a.findViewById(R.id.advancedButton).getVisibility()==View.GONE,"Advanced settings shown for built-in offer");
                assertUnclippedText(a.demoRadio);assertUnclippedText(a.xtream);assertUnclippedText(a.m3uRadio);
            });
            snapshot("onboarding-"+language);
            runOnMainSync(()->{
                a.xtream.performClick();
                check(a.xtreamFields.getVisibility()==View.VISIBLE&&a.m3uFields.getVisibility()==View.GONE,"Xtream fields missing");
                a.m3uRadio.performClick();
                check(a.m3uFields.getVisibility()==View.VISIBLE&&a.xtreamFields.getVisibility()==View.GONE,"M3U fields missing");
                a.finish();
            });
        }
        Profile demo=new Profile();demo.type=Profile.Type.M3U;demo.m3uUrl=BuildConfig.NENOTV_DEMO_M3U_URL;demo.name="Saved NenoTV";profiles.save(demo);
        ProfileActivity saved=(ProfileActivity)startActivitySync(new Intent(c,ProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        runOnMainSync(()->{check(saved.demoRadio.isChecked(),"Saved NenoTV offer reopened as own M3U");saved.finish();});
        ProfileActivity added=(ProfileActivity)startActivitySync(new Intent(c,ProfileActivity.class).putExtra("new_source",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        runOnMainSync(()->{check(added.xtream.isChecked(),"Adding own source changed default");added.finish();});
        String previousStart=SettingsStore.startScreen(c);
        SettingsStore.prefs(c).edit().putString("start_screen","vod").commit();
        MainActivity films=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            java.util.concurrent.atomic.AtomicBoolean visible=new java.util.concurrent.atomic.AtomicBoolean();
            long deadline=SystemClock.elapsedRealtime()+10000;
            while(!visible.get()&&SystemClock.elapsedRealtime()<deadline){runOnMainSync(()->visible.set(films.gridAdapter.getCount()>0));Thread.sleep(100);}
            check(visible.get(),"Built-in NenoTV films blocked as M3U live-only");
            snapshot("nenotv-offer-films");
        }finally{runOnMainSync(films::finish);SettingsStore.prefs(c).edit().putString("start_screen",previousStart).commit();}
        accountChecks();
        result.putString("NENOTV_ONBOARDING","passed");
    }
    void accountChecks()throws Exception{
        Context c=getTargetContext();
        com.nenotv.player.entitlement.EntitlementClientChecks.run(c);
        for(String language:new String[]{"nl","en","de"}){
            SettingsStore.setPrimaryLanguage(c,language);
            AccountActivity a=(AccountActivity)startActivitySync(new Intent(c,AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                waitForIdleSync();
                runOnMainSync(()->{
                    check(!a.activationCode.isSaveEnabled(),"Activation code saved in activity state");
                    check(a.activationCode.getImportantForAutofill()==View.IMPORTANT_FOR_AUTOFILL_NO,"Activation code offered to autofill");
                    a.activationCode.setText("invalid");a.link.performClick();
                    check(a.activationCode.getError()!=null&&!a.requestRunning,"Invalid code contacted account server");
                    a.activationCode.setText("");a.activationCode.setError(null);a.activationCode.clearFocus();
                    a.busy(true);
                    check(!a.link.isEnabled()&&!a.claim.isEnabled()&&!a.trial.isEnabled()&&!a.refresh.isEnabled(),"Account permits concurrent requests");
                    a.busy(false);
                    assertUnclippedText(a.link);
                    if(Build.VERSION.SDK_INT>=30){
                        int[] position=new int[2];a.box.getLocationOnScreen(position);
                        check(position[1]>=a.box.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()).top,"Account overlaps system bars");
                    }
                });
                snapshot("account-"+language);
            }finally{runOnMainSync(a::finish);waitForIdleSync();}
        }
    }
    void core(Bundle result)throws Exception{
        XtreamImportChecks.run(getTargetContext());
        xtreamSharedDownloads();
        result.putString("NENOTV_XTREAM_IMPORT","passed");
        onboarding(result);
        Context c=getTargetContext();
        for(String unknown:new String[]{"0","0.0","null","NaN","Infinity","-1","11",""})check(MediaEntry.formatRating(unknown).isEmpty(),"Unknown rating shown: "+unknown);
        check(!MediaEntry.formatRating("8,2").isEmpty(),"Comma score disappeared");
        MediaDetails details=new MediaDetails();details.imdbRating="0";details.tmdbRating="8.2";check(details.scoreLabel().startsWith("TMDb"),"Invalid IMDb score hid valid TMDb score");
        LibraryStore store=new LibraryStore(c);MediaEntry one=episode(1,1),two=episode(1,2),ten=episode(1,10),nextSeason=episode(2,1);
        List<MediaEntry> episodes=Arrays.asList(nextSeason,ten,two,one);for(MediaEntry e:episodes)store.clearProgress(e);
        List<MediaEntry> sorted=EpisodeOrder.sorted(episodes);check(sorted.get(0).episode==1&&sorted.get(1).episode==2&&sorted.get(2).episode==10&&sorted.get(3).season==2,"Episode order is lexical");
        store.recent(one);check(EpisodeOrder.next(episodes,store).uniqueKey().equals(one.uniqueKey()),"Opening advanced episode");
        store.saveProgress(one,40000,100000,true);check(!store.watched(one)&&store.progress(one)==40000,"Short film completed at 40%");
        store.recent(two);store.saveProgress(two,20000,100000,true);check(EpisodeOrder.next(episodes,store).uniqueKey().equals(two.uniqueKey()),"Older partial episode won over latest viewing");
        store.markWatched(two);check(EpisodeOrder.next(episodes,store).uniqueKey().equals(ten.uniqueKey()),"Completed episode did not advance");
        store.saveProgress(ten,96000,100000,true);store.recent(ten);check(store.watched(ten)&&store.progress(ten)==0,"Completed episode stayed in continue watching");
        check(EpisodeOrder.next(episodes,store).uniqueKey().equals(nextSeason.uniqueKey()),"Season boundary skipped");
        SettingsStore.prefs(c).edit().putString("qa_ui_resume_id",one.id).commit();
        result.putString("NENOTV_UI_CORE","passed");
        for(String language:new String[]{"nl","en","de","es","fr","it","pt","tr","pl","ar"})for(String key:new String[]{"season","all_seasons","remaining","watched","show_cast"})check(("en".equals(language)&&"remaining".equals(key))||!key.equals(UiText.t(language,key)),"Missing translation "+language+":"+key);
        final long now=System.currentTimeMillis()/1000L;
        final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
        final List<EpgEntry> input=Arrays.asList(programme("Next fixture",now+300,now+900),programme("Now fixture",now-300,now+300),programme("Invalid",now+900,now+800),programme("Now fixture",now-300,now+300));
        final MediaEntry channel=new MediaEntry();channel.id="ui-channel";channel.name="QA Live";channel.type="live";
        final MediaEntry series=new MediaEntry();series.id="ui-series";series.seriesId="ui-series";series.name="QA Series";series.type="series";
        Provider fixture=new Provider(){public void authenticate(){}public List<Category> categories(String type){return Collections.singletonList(new Category("all","NL | QA",type));}public List<MediaEntry> items(String type,String category){return "live".equals(type)?Collections.singletonList(channel):"series".equals(type)?Collections.singletonList(series):Collections.emptyList();}public List<MediaEntry> seriesEpisodes(MediaEntry ignored){return episodes;}public List<EpgEntry> epgEntries(MediaEntry ignored,int limit){requests.incrementAndGet();return input;}};
        EpgStore epg=new EpgStore(c);String key="ui-epg-"+System.nanoTime();List<EpgEntry> first=epg.getOrFetch(fixture,key,channel),second=epg.getOrFetch(fixture,key,channel);check(requests.get()==1&&first.size()==2&&second.size()==2,"EPG did not share a normalized snapshot");check("Now fixture".equals(EpgTimeline.now(second,now).title)&&"Next fixture".equals(EpgTimeline.next(second,now).title),"Now/next changed after cache");check(EpgTimeline.now(second,now+1000)==null,"Old programme labelled Now");epg.close();
        result.putString("NENOTV_EPG_SHARED","passed");
        SecureProfileStore profiles=new SecureProfileStore(c);com.nenotv.player.model.Profile p=new com.nenotv.player.model.Profile();p.type=com.nenotv.player.model.Profile.Type.M3U;p.m3uUrl=DemoSource.URL;p.name="UI QA";
        SettingsStore.prefs(c).edit().remove("demo_consumed").remove("demo_expires_at").remove("demo_started_at").commit();DemoPolicy.startOrKeep(c,System.currentTimeMillis());profiles.save(p);
        c.getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE).edit().putString("level","FREE").putLong("expires_at",0).commit();
        check("LIGHT".equals(new EntitlementStore(c).shortBadge()),"Light called Free");
        for(String language:new String[]{"nl","en","de"}){
            SettingsStore.setPrimaryLanguage(c,language);
            MainActivity a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();Thread.sleep(2000);
            runOnMainSync(()->{a.nextRequest();if(a.indexFuture!=null)a.indexFuture.cancel(true);a.provider=fixture;a.selectedHero=null;a.select(series);});
            long deadline=SystemClock.elapsedRealtime()+15000;while(a.seasonSpinner.getVisibility()!=View.VISIBLE&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);waitForIdleSync();
            runOnMainSync(()->{check(a.seasonSpinner.getVisibility()==View.VISIBLE,"Season choice missing in "+language);a.seasonSpinner.setSelection(2);});waitForIdleSync();Thread.sleep(200);
            runOnMainSync(()->{check(a.adapter.getCount()==1&&a.adapter.getItem(0).season==2,"Season filter used wrong items");a.seasonSpinner.setSelection(0);});waitForIdleSync();Thread.sleep(100);snapshot("seasons-"+language);
            runOnMainSync(()->{a.hideSeasons();a.section="epg";a.all=Collections.singletonList(channel);a.setEpgMode(true);});waitForIdleSync();Thread.sleep(400);snapshot("epg-grid-"+language);
            runOnMainSync(()->a.setEpgMode(false));waitForIdleSync();Thread.sleep(400);
            final android.view.View[] row={null};runOnMainSync(()->{row[0]=a.epgAdapter.getView(0,null,a.list);});Thread.sleep(300);runOnMainSync(()->row[0]=a.epgAdapter.getView(0,row[0],a.list));
            check(((TextView)row[0].findViewById(R.id.epgNow)).getText().toString().contains("Now fixture"),"List disagrees with timeline");snapshot("epg-list-"+language);
            runOnMainSync(()->{View nav=a.findViewById(R.id.bottomNav);int[] xy=new int[2];nav.getLocationOnScreen(xy);WindowInsets insets=a.getWindow().getDecorView().getRootWindowInsets();int bottom=Build.VERSION.SDK_INT>=30&&insets!=null?insets.getInsets(WindowInsets.Type.systemBars()).bottom:0;check(xy[1]+nav.getHeight()<=a.getResources().getDisplayMetrics().heightPixels-bottom+2,"Bottom navigation under system bar");check(a.grid.getPaddingBottom()>0&&!a.grid.getClipToPadding(),"Last row has no safe padding");a.loadHome();});waitForIdleSync();snapshot("home-"+language);
            if("tv".equals(args.getString("device"))){setInTouchMode(false);runOnMainSync(()->{a.showMediaGrid(false);a.gridAdapter.set(episodes,false);a.grid.requestFocus();a.grid.setSelection(0);});waitForIdleSync();Thread.sleep(200);runOnMainSync(()->{check(a.grid.hasFocus(),"TV grid did not gain focus");check(a.grid.getSelectedItemPosition()==0,"TV grid did not select first card");});sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);waitForIdleSync();runOnMainSync(()->check(a.grid.getSelectedItemPosition()>0,"TV remote did not move selection"));snapshot("tv-focus-"+language);}
            MediaDetails info=new MediaDetails();info.title="QA film with a longer title";info.plot="A fixture description.";info.cast="Actor A, Actor B";info.year="2026";info.rating="8.2";
            final Dialog[] detailsDialog={null};runOnMainSync(()->detailsDialog[0]=a.showDetailsDialog(one,info));waitForIdleSync();long dialogDeadline=SystemClock.elapsedRealtime()+5000;android.view.accessibility.AccessibilityNodeInfo active=null;boolean visible=false;while(!visible&&SystemClock.elapsedRealtime()<dialogDeadline){active=getUiAutomation().getRootInActiveWindow();visible=active!=null&&!active.findAccessibilityNodeInfosByText("QA film").isEmpty();if(!visible)Thread.sleep(150);}if(!visible)snapshot("film-dialog-failure");check(visible,"Film detail dialog did not become visible: "+String.valueOf(active));runOnMainSync(()->assertUnclippedText((TextView)detailsDialog[0].getWindow().getDecorView().findViewWithTag("details-favorite")));runOnMainSync(()->detailsDialog[0].getWindow().getDecorView().findViewWithTag("details-favorite").performClick());waitForIdleSync();runOnMainSync(()->assertUnclippedText((TextView)detailsDialog[0].getWindow().getDecorView().findViewWithTag("details-favorite")));snapshot("film-info-"+language);sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            try(ImageFixture images=new ImageFixture()){
                final ImageView[] image={null};runOnMainSync(()->{image[0]=new ImageView(a);((ViewGroup)a.findViewById(android.R.id.content)).addView(image[0],new ViewGroup.LayoutParams(1,1));MediaRowAdapter.loadArtwork(image[0],images.url("ok"),"QA",240,360);});
                long end=SystemClock.elapsedRealtime()+10000;while(!(image[0].getDrawable() instanceof android.graphics.drawable.BitmapDrawable)&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
                check(image[0].getDrawable() instanceof android.graphics.drawable.BitmapDrawable,"Valid poster did not decode");
                runOnMainSync(()->MediaRowAdapter.loadArtwork(image[0],images.url("missing"),"QA",240,360));Thread.sleep(500);check(image[0].getDrawable()!=null&&!(image[0].getDrawable() instanceof android.graphics.drawable.BitmapDrawable),"Failed poster left old image or blank card");
            }
            runOnMainSync(a::finish);
        }
        result.putString("NENOTV_UI_SCREENSHOTS","passed");profiles.clear();finish(Activity.RESULT_OK,result);
    }
    void resume(Bundle result){LibraryStore store=new LibraryStore(getTargetContext());MediaEntry one=episode(1,1),two=episode(1,2);check(store.progress(one)==40000,"Progress lost after force-stop");check(store.watched(two),"Completed flag lost after force-stop");result.putString("NENOTV_UI_RESUME","passed");finish(Activity.RESULT_OK,result);}
    void pro(Bundle result)throws Exception{
        Context c=getTargetContext();SettingsStore.setPrimaryLanguage(c,"nl");
        MainActivity a=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();Thread.sleep(1000);
        check(ProModuleInstaller.isInstalled(a),"Fused Pro module is invisible");
        check(!ProLibraryBridge.isActive(a),"Light gained Pro without entitlement");
        check(ProModuleInstaller.playerIntent(a).getComponent().getClassName().equals(PlayerActivity.class.getName()),"Light bypassed basic player");
        check(ProModuleInstaller.sourcesIntent(a).getComponent().getClassName().equals(ProfileActivity.class.getName()),"Light reached Pro source manager");
        com.nenotv.player.provider.M3uProvider source=new com.nenotv.player.provider.M3uProvider(profile());source.authenticate();MediaEntry basicItem=source.items("vod","all").get(0);
        PlayerActivity basic=(PlayerActivity)startActivitySync(new Intent(c,PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("media",basicItem));waitForIdleSync();
        long ready=SystemClock.elapsedRealtime()+90000;final java.util.concurrent.atomic.AtomicBoolean basePlaying=new java.util.concurrent.atomic.AtomicBoolean();while(!basePlaying.get()&&SystemClock.elapsedRealtime()<ready){runOnMainSync(()->basePlaying.set(basic.exo!=null&&basic.exo.getCurrentPosition()>1500&&basic.exo.getVideoFormat()!=null&&basic.exo.getAudioFormat()!=null));Thread.sleep(100);}check(basePlaying.get(),"Light player did not play demo");
        runOnMainSync(()->{WindowInsets in=basic.getWindow().getDecorView().getRootWindowInsets();if(Build.VERSION.SDK_INT>=30)check(in!=null&&!in.isVisible(WindowInsets.Type.systemBars()),"Light player is not fullscreen");basic.showControls();check(basic.forward.getText().toString().contains("10"),"Forward control is unclear");basic.forward.performClick();});Thread.sleep(300);runOnMainSync(()->{check(basic.exo.getCurrentPosition()>=10000,"Forward did not seek 10 seconds");basic.rewind.performClick();});Thread.sleep(300);runOnMainSync(()->check(basic.exo.getCurrentPosition()<5000,"Rewind did not seek back"));snapshot("light-player");runOnMainSync(basic::finish);waitForIdleSync();
        c.getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE).edit().putString("level","PRO").commit();
        check(ProLibraryBridge.isActive(a),"Pro entitlement did not enable module");
        check(ProModuleInstaller.sourcesIntent(a).getComponent().getClassName().contains("ProSourcesActivity"),"Pro source manager route missing");
        check(ProModuleInstaller.networkIntent(a).getComponent().getClassName().contains("ProNetworkActivity"),"Pro network route missing");
        MediaEntry langEn=new MediaEntry();langEn.type="live";langEn.id="lang-en";langEn.name="EN - QA";langEn.group="EN | General";
        MediaEntry langNl=new MediaEntry();langNl.type="live";langNl.id="lang-nl";langNl.name="NL - QA";langNl.group="NL | Algemeen";
        List<MediaEntry> ranked=ProLibraryBridge.optimize(a,Arrays.asList(langEn,langNl),"live","nl");
        check(ranked.size()==2&&ranked.get(0)==langNl,"Pro preferred-language optimizer did not rank Dutch first");
        MediaEntry sd=new MediaEntry();sd.type="live";sd.id="sd";sd.name="A Channel SD";sd.group="NL | General";
        MediaEntry hd=new MediaEntry();hd.type="live";hd.id="hd";hd.name="Z Channel HD";hd.group="NL | General";
        MediaEntry radio=new MediaEntry();radio.type="live";radio.id="radio";radio.name="A FM";radio.group="NL | General";
        MediaEntry npo=new MediaEntry();npo.type="live";npo.id="npo";npo.name="NPO 1 HD";npo.group="NL | General";
        List<MediaEntry> quality=ProLibraryBridge.optimize(a,Arrays.asList(sd,radio,hd,npo),"live","nl");
        check(quality.get(0)==npo&&quality.get(1)==hd&&quality.get(3)==radio,"Pro quality/main-channel/radio ranking failed");
        result.putString("NENOTV_PRO_LANGUAGE","passed");
        SourceStore sourceStore=new SourceStore(c);
        com.nenotv.player.model.Profile qaA=new com.nenotv.player.model.Profile();qaA.type=com.nenotv.player.model.Profile.Type.M3U;qaA.name="QA Source A";qaA.m3uUrl=DemoSource.URL;
        com.nenotv.player.model.Profile qaB=new com.nenotv.player.model.Profile();qaB.type=com.nenotv.player.model.Profile.Type.XTREAM;qaB.name="QA Source B";qaB.server="https://example.invalid";qaB.username="qa";qaB.password="secret";
        String qaAId=sourceStore.upsert("",qaA,true),qaBId=sourceStore.upsert("",qaB,false);
        check(sourceStore.list().size()>=2,"Pro multi-source registry did not retain multiple sources");
        check(sourceStore.setActive(qaBId)&&"QA Source B".equals(new SecureProfileStore(c).load().name),"Active Pro source did not mirror into Light provider profile");
        sourceStore.setEnabled(qaBId,false);String fallbackId=sourceStore.activeId();SourceStore.Entry fallbackEntry=null;for(SourceStore.Entry candidate:sourceStore.list())if(candidate.id.equals(fallbackId)){fallbackEntry=candidate;break;}check(!qaBId.equals(fallbackId)&&fallbackEntry!=null&&fallbackEntry.enabled&&fallbackEntry.profile.name.equals(new SecureProfileStore(c).load().name),"Disabling active source did not fail over the Light profile");
        sourceStore.setEnabled(qaBId,true);check(sourceStore.setActive(qaBId),"Re-enabled source could not become active again");
        org.json.JSONArray syncCopy=sourceStore.exportForSync();check(syncCopy.length()>=2,"Source sync export lost entries");
        sourceStore.markSynced(1);
        sourceStore.applyCloudSnapshot(syncCopy,2);check(sourceStore.list().size()>=2,"Source sync snapshot lost entries");

        MediaEntry primary=new MediaEntry();primary.type="live";primary.id="primary";primary.name="QA Channel HD";primary.tvgId="qa-channel";primary.candidates.add("https://primary.invalid/live");
        MediaEntry secondary=new MediaEntry();secondary.type="live";secondary.id="secondary";secondary.name="QA Channel";secondary.tvgId="qa-channel";secondary.sourceId=qaBId;secondary.candidates.add("https://fallback.invalid/live");
        List<MediaEntry> smartMerged=SmartSourceMerger.merge(Collections.singletonList(primary),Collections.singletonList(secondary));
        check(smartMerged.size()==1&&smartMerged.get(0).candidates.size()==2,"Smart Sources did not dedupe and preserve fallback streams");
        result.putString("NENOTV_PRO_SMART_SOURCES","passed");

        try(EpgFixture epgFixture=new EpgFixture()){
            MediaEntry smartChannel=new MediaEntry();smartChannel.type="live";smartChannel.id="smart-epg";smartChannel.name="QA Smart";smartChannel.tvgId="qa-smart";smartChannel.tvgName="QA Smart";smartChannel.sourceId=qaAId;
            new SmartEpgStore(c).setUrls(qaAId,Collections.singletonList(epgFixture.url()));
            SettingsStore.prefs(c).edit().putBoolean("pro_smart_epg",true).commit();sourceStore.touchSync();
            Provider noGuide=new Provider(){public void authenticate(){}public List<Category> categories(String type){return Collections.emptyList();}public List<MediaEntry> items(String type,String category){return Collections.emptyList();}public List<EpgEntry> epgEntries(MediaEntry item,int limit)throws Exception{throw new IOException("QA provider EPG failure");}};
            EpgStore smartStore=new EpgStore(c);List<EpgEntry> smartRows=smartStore.getOrFetch(noGuide,"qa-smart-"+System.nanoTime(),smartChannel);smartStore.close();
            check(!smartRows.isEmpty()&&"Smart EPG fixture".equals(smartRows.get(0).title),"Smart EPG did not fall back to the extra XMLTV source");
            org.json.JSONArray exported=sourceStore.exportForSync();boolean epgSynced=false;for(int q=0;q<exported.length();q++){org.json.JSONObject o=exported.optJSONObject(q);if(o!=null&&qaAId.equals(o.optString("id"))&&o.optJSONArray("epg_extra")!=null&&o.optJSONArray("epg_extra").length()==1)epgSynced=true;}
            check(epgSynced,"Smart EPG URLs were not included in My NenoTV source sync");
            result.putString("NENOTV_PRO_SMART_EPG","passed");
        }

        sourceStore.remove(qaAId);check(sourceStore.syncDirty(),"Local source deletion did not mark sync dirty");
        result.putString("NENOTV_PRO_SOURCES","passed");
        sourceStore.remove(qaBId);
        // Source-manager checks remove the active profile; restore the demo for playback policy checks.
        new SecureProfileStore(c).save(profile());
        check(DemoPolicy.isDemo(new SecureProfileStore(c).load()),"Playback fixture lost its demo profile");
        // Build the provider before selecting the packaged entry.
        com.nenotv.player.provider.M3uProvider provider=new com.nenotv.player.provider.M3uProvider(profile());provider.authenticate();MediaEntry item=provider.items("vod","all").get(0);
        Intent i=ProModuleInstaller.playerIntent(a);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);i.putExtra("media",item);
        Activity player=startActivitySync(i);waitForIdleSync();
        check(player.getClass().getName().contains("ProPlayerActivity"),"Pro did not route to its player");
        java.lang.reflect.Field field=player.getClass().getDeclaredField("exo");field.setAccessible(true);final java.util.concurrent.atomic.AtomicBoolean playing=new java.util.concurrent.atomic.AtomicBoolean();
        long end=SystemClock.elapsedRealtime()+90000;while(!playing.get()&&SystemClock.elapsedRealtime()<end){runOnMainSync(()->{try{androidx.media3.exoplayer.ExoPlayer exo=(androidx.media3.exoplayer.ExoPlayer)field.get(player);playing.set(exo!=null&&exo.getCurrentPosition()>1500&&exo.getVideoFormat()!=null&&exo.getAudioFormat()!=null);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(100);}
        check(playing.get(),"Actual Pro player did not play packaged demo");snapshot("pro-player");
        runOnMainSync(()->{if(Build.VERSION.SDK_INT>=30){WindowInsets in=player.getWindow().getDecorView().getRootWindowInsets();check(in!=null&&!in.isVisible(WindowInsets.Type.systemBars()),"Pro player is not fullscreen");}});
        SettingsStore.prefs(c).edit().putLong("demo_expires_at",System.currentTimeMillis()-1).commit();
        long stop=SystemClock.elapsedRealtime()+5000;while(!player.isFinishing()&&!player.isDestroyed()&&SystemClock.elapsedRealtime()<stop)Thread.sleep(100);check(player.isFinishing()||player.isDestroyed(),"Open Pro player ignored demo expiry");
        c.getSharedPreferences("nenotv_entitlement",Context.MODE_PRIVATE).edit().putString("level","FREE").commit();new SecureProfileStore(c).clear();runOnMainSync(a::finish);result.putString("NENOTV_PRO_RUNTIME","passed");finish(Activity.RESULT_OK,result);
    }
    static com.nenotv.player.model.Profile profile(){com.nenotv.player.model.Profile p=new com.nenotv.player.model.Profile();p.type=com.nenotv.player.model.Profile.Type.M3U;p.m3uUrl=DemoSource.URL;p.name="Demo QA";return p;}
    @Override public void onStart(){Bundle result=new Bundle();String phase=args.getString("phase","ui");if(!Arrays.asList("ui","resume","pro","update","xtream","onboarding").contains(phase)){super.onStart();return;}try{if("onboarding".equals(phase)){onboarding(result);finish(Activity.RESULT_OK,result);}else if("xtream".equals(phase)){XtreamImportChecks.run(getTargetContext());xtreamSharedDownloads();result.putString("NENOTV_XTREAM_IMPORT","passed");finish(Activity.RESULT_OK,result);}else if("update".equals(phase)){UpdateAccessChecks.run(getTargetContext());result.putString("NENOTV_UPDATE_ACCESS","passed");finish(Activity.RESULT_OK,result);}else if("resume".equals(phase))resume(result);else if("pro".equals(phase)){Context c=getTargetContext();SettingsStore.prefs(c).edit().remove("demo_consumed").remove("demo_expires_at").remove("demo_started_at").commit();DemoPolicy.startOrKeep(c,System.currentTimeMillis());new SecureProfileStore(c).save(profile());pro(result);}else core(result);}catch(Throwable failure){result.putString("NENOTV_UI_TESTS","failed: "+failure.getClass().getSimpleName()+": "+failure.getMessage());finish(Activity.RESULT_CANCELED,result);}}
    static final class EpgFixture implements AutoCloseable{
        final ServerSocket socket;final Thread worker;final byte[] body;
        EpgFixture()throws Exception{
            socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
            String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"qa-smart\"><display-name>QA Smart</display-name></channel><programme start=\"20990101000000 +0000\" stop=\"20990101010000 +0000\" channel=\"qa-smart\"><title lang=\"nl\">Smart EPG fixture</title><desc lang=\"nl\">Fallback guide</desc></programme></tv>";
            body=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            worker=new Thread(()->{while(!socket.isClosed())try(Socket s=socket.accept()){BufferedReader reader=new BufferedReader(new InputStreamReader(s.getInputStream()));String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));out.write(body);out.flush();}catch(Exception ignored){}},"epg-fixture");worker.setDaemon(true);worker.start();
        }
        String url(){return "http://127.0.0.1:"+socket.getLocalPort()+"/guide.xml";}
        public void close()throws Exception{socket.close();worker.join(1000);}
    }

    static final class ImageFixture implements AutoCloseable{
        final ServerSocket socket;final byte[] png;final Thread worker;
        ImageFixture()throws Exception{socket=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));Bitmap b=Bitmap.createBitmap(32,48,Bitmap.Config.RGB_565);b.eraseColor(0xFF005A9C);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);png=out.toByteArray();b.recycle();worker=new Thread(()->{while(!socket.isClosed())try(Socket s=socket.accept()){BufferedReader reader=new BufferedReader(new InputStreamReader(s.getInputStream()));String request=reader.readLine(),line;while((line=reader.readLine())!=null&&!line.isEmpty()){}boolean ok=request!=null&&request.contains("/ok ");byte[] body=ok?png:new byte[0];OutputStream response=s.getOutputStream();response.write(((ok?"HTTP/1.1 200 OK":"HTTP/1.1 404 Not Found")+"\r\nContent-Type: image/png\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));response.write(body);response.flush();
            }catch(Exception ignored){}},"poster-fixture");worker.setDaemon(true);worker.start();}
        String url(String path){return "http://127.0.0.1:"+socket.getLocalPort()+"/"+path;}
        public void close()throws Exception{socket.close();worker.join(1000);}
    }
}
