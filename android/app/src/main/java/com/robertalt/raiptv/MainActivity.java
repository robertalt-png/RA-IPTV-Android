package com.nenotv.player;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.*;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.util.LruCache;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.mediarouter.app.MediaRouteChooserDialog;
import androidx.mediarouter.media.MediaRouteSelector;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.nenotv.player.model.*;
import com.nenotv.player.provider.*;
import com.nenotv.player.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.net.*;

public class MainActivity extends Activity {
    private PlayUpdateNotifier updateNotifier;
    private SourceProviderResolver sourceResolver;
    Spinner seasonSpinner;boolean settingSeasons=false;List<MediaEntry> seriesEpisodes=new ArrayList<>();
    SecureProfileStore profiles; LibraryStore library; SearchIndexStore searchIndex; EpgStore epgStore;
    Profile profile; Provider provider; boolean profileReady=false; final ConcurrentHashMap<String,Provider> smartProviders=new ConcurrentHashMap<>(); volatile String smartMergeKey="";
    ExecutorService exec=Executors.newFixedThreadPool(3), heroExec=Executors.newSingleThreadExecutor(), indexExec=Executors.newSingleThreadExecutor(); ThreadPoolExecutor epgExec=(ThreadPoolExecutor)Executors.newFixedThreadPool(2); volatile int epgRequestSerial=0;
    Spinner categories; ListView list; GridView grid; EditText search; TextView status,title,heroTitle,heroSubtitle,indexBannerText; ImageView heroImage; Button heroAction,heroInfo,genreButton,sortButton,searchToggle,settingsButton,tvShareButton,epgGridButton,epgListButton,languageBadge,planBadge; ProgressBar progress,indexBannerProgress;
    ScrollView browseScroll,epgBoard; LinearLayout browseContainer,epgBoardContainer,indexBanner; View filterBar,epgModeBar;
    MediaRowAdapter adapter; MediaGridAdapter gridAdapter; EpgAdapter epgAdapter;
    List<MediaEntry> all=new ArrayList<>(); List<Category> currentCategories=new ArrayList<>();
    String section="home", currentCategoryId="", currentCategoryName="";
    volatile boolean activityPaused=false,playbackActive=false;
    boolean seriesEpisodeMode=false,settingCategories=false,autoDefaultGroup=true; volatile boolean indexRefreshRequested=false,indexRefreshRunning=false,resumeIndexAfterPlayback=false,indexCategoryBusy=false; volatile Future<?> indexFuture=null; volatile int fullLibraryToken=0; String appliedLanguage="",appliedContentLanguage="";
    boolean cachePagingActive=false,cachePageLoading=false,autoReindexAfterConnect=false,epgGridMode=true; String cachePagingSection="",cachePagingTag=""; int cachePagingOffset=0,cachePagingTotal=0; static final int CACHE_PAGE_SIZE=240; long lastIndexUiPublish=0L; String indexAutoHeroKey="";
    int requestSerial=0,heroSerial=0; String latestSearchQuery=""; long epgBaseEpoch=0L;
    Handler ui=new Handler(Looper.getMainLooper()); Runnable pendingSearch,delayedIndexResume; MediaEntry selectedHero;
    private long familyRevision=-1;
    static final long SEARCH_INDEX_TTL_MS=6*60*60*1000L;
    static final int SEARCH_INDEX_GENERATION=10;
    static final LruCache<String,Bitmap> HERO_CACHE=new LruCache<String,Bitmap>(4*1024){@Override protected int sizeOf(String k,Bitmap b){return Math.max(1,b.getByteCount()/1024);}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);InfoTranslator.init(this);CrashGuard.install(this);SettingsStore.migrateLanguagePreferences(this);if(!SettingsStore.hasLanguageProfile(this)){startActivity(new Intent(this,LanguageSetupActivity.class));finish();return;}
        if(!com.nenotv.player.storage.ExtraPrivacyStore.answered(this)&&!FamilyStore.active(this)){startActivity(new Intent(this,AgePrivacyActivity.class));finish();return;}
        AccountLinkStore account=new AccountLinkStore(this);if(!account.recent()){startActivity(!account.linked()&&!FamilyStore.active(this)?firstRunIntent(this):new Intent(this,AccountCheckActivity.class));finish();return;}
        setContentView(R.layout.activity_main);UiText.applyDirection(this);
        profiles=new SecureProfileStore(this);library=new LibraryStore(this);searchIndex=new SearchIndexStore(this);epgStore=new EpgStore(this);
        categories=findViewById(R.id.categorySpinner);list=findViewById(R.id.itemList);grid=findViewById(R.id.itemGrid);search=findViewById(R.id.searchBox);searchToggle=findViewById(R.id.searchToggle);settingsButton=findViewById(R.id.settingsButton);tvShareButton=findViewById(R.id.tvShareButton);status=findViewById(R.id.status);indexBanner=findViewById(R.id.indexBanner);indexBannerText=findViewById(R.id.indexBannerText);indexBannerProgress=findViewById(R.id.indexBannerProgress);languageBadge=findViewById(R.id.languageBadge);planBadge=findViewById(R.id.planBadge);title=findViewById(R.id.title);heroTitle=findViewById(R.id.heroTitle);heroSubtitle=findViewById(R.id.heroSubtitle);heroImage=findViewById(R.id.heroImage);heroAction=findViewById(R.id.heroAction);heroInfo=findViewById(R.id.heroInfo);progress=findViewById(R.id.progress);genreButton=findViewById(R.id.genreButton);sortButton=findViewById(R.id.sortButton);browseScroll=findViewById(R.id.browseScroll);browseContainer=findViewById(R.id.browseContainer);filterBar=findViewById(R.id.filterBar);epgModeBar=findViewById(R.id.epgModeBar);epgGridButton=findViewById(R.id.epgGridButton);epgListButton=findViewById(R.id.epgListButton);epgBoard=findViewById(R.id.epgBoard);epgBoardContainer=findViewById(R.id.epgBoardContainer);
        ScreenInsets.browsing(this);
        adapter=new MediaRowAdapter(this,library);gridAdapter=new MediaGridAdapter(this,library);epgAdapter=new EpgAdapter(this,epgStore);list.setAdapter(adapter);grid.setAdapter(gridAdapter);appliedLanguage=SettingsStore.language(this);appliedContentLanguage=SettingsStore.contentLanguage(this);applyStaticLanguage();wire();wireSeasons();updateHeaderBadges();restoreFirstSyncBanner();
        if(expireDemoProfileIfNeeded()){startActivityForResult(new Intent(this,ProfileActivity.class),10);return;}
        if(!profiles.exists())startActivityForResult(new Intent(this,ProfileActivity.class),10);else openProfile();
    }


    boolean expireDemoProfileIfNeeded(){
        if(!profiles.exists()||!DemoPolicy.expired(this))return false;
        try{
            Profile p=profiles.load();
            if(!DemoPolicy.isDemo(p))return false;
            profiles.clear();
            String l=SettingsStore.language(this);
            String msg="nl".equals(l)?"Uw 30 dagen demo is afgelopen. Voeg uw eigen M3U- of Xtream-bron toe.":"de".equals(l)?"Ihre 30-Tage-Demo ist beendet. Fügen Sie eine eigene M3U- oder Xtream-Quelle hinzu.":"Your 30-day demo has ended. Add your own M3U or Xtream source.";
            Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
            return true;
        }catch(Exception ignored){return false;}
    }

    @Override protected void onPostResume(){
        super.onPostResume();
        if(isFinishing()||isDestroyed())return;
        if(library!=null&&!library.viewerId.equals(new HouseholdProfileStore(this).activeId())){recreate();return;}
        android.content.SharedPreferences settings=SettingsStore.prefs(this);
        if(settings.getBoolean("account_sources_changed",false)){
            settings.edit().remove("account_sources_changed").apply();recreate();return;
        }
        com.nenotv.player.entitlement.AutomaticSourceDownload.check(this,()->runOnUiThread(()->{
            settings.edit().putBoolean("account_sources_changed",true).apply();
            if(!isFinishing()&&!isDestroyed()&&!activityPaused&&!playbackActive){
                settings.edit().remove("account_sources_changed").apply();recreate();
            }
        }));
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig){
        int first=grid==null?0:grid.getFirstVisiblePosition();
        View firstView=grid==null?null:grid.getChildAt(0);
        int top=firstView==null?0:firstView.getTop();
        super.onConfigurationChanged(newConfig);
        setHeroHeight(heroHeight());
        if(grid!=null&&grid.getVisibility()==View.VISIBLE){
            int sw=newConfig.screenWidthDp;
            grid.setNumColumns("live".equals(section)?(sw>=600?4:2):(sw>=600?6:3));
            final int keepFirst=first,keepTop=top;
            grid.post(()->{if(!isFinishing()&&!isDestroyed())grid.setSelectionFromTop(keepFirst,keepTop);});
        }
    }

    synchronized void migrateSearchIndexIfNeeded(){
        android.content.SharedPreferences sp=SettingsStore.prefs(this);
        int have=sp.getInt("search_index_generation",0);
        if(have>=SEARCH_INDEX_GENERATION)return;
        if(have<SEARCH_INDEX_GENERATION)try{searchIndex.clearAll();}catch(Exception ignored){}
        android.content.SharedPreferences.Editor ed=sp.edit().putInt("search_index_generation",SEARCH_INDEX_GENERATION);
        if(have<SEARCH_INDEX_GENERATION)try{for(String k:sp.getAll().keySet())if(k!=null&&k.startsWith("cache_cursor_"))ed.remove(k);}catch(Exception ignored){}
        ed.apply();
        autoReindexAfterConnect=true;
    }

    String T(String key){return UiText.t(this,key);}
    void applyStaticLanguage(){
        title.setText("SunnyIPTV");title.setTextSize(17);title.setLetterSpacing(0.02f);title.setTextColor(0xFFFFD400);title.setSingleLine(true);title.setTypeface(null,Typeface.BOLD);
        ((Button)findViewById(R.id.profileButton)).setText(T("profile"));
        ((Button)findViewById(R.id.navHome)).setText("⌂\n"+T("home"));
        ((Button)findViewById(R.id.navLive)).setText("●\n"+T("live"));
        ((Button)findViewById(R.id.navEpg)).setText("▦\n"+T("epg"));
        ((Button)findViewById(R.id.navMovies)).setText("▣\n"+T("movies"));
        ((Button)findViewById(R.id.navSeries)).setText("▤\n"+T("series"));
        genreButton.setText(T("genre")+" ▾");sortButton.setText(T("sort"));epgGridButton.setText("🔒 PRO · "+T("advanced_epg"));epgListButton.setText(T("list"));heroInfo.setText("ⓘ "+T("info"));search.setHint(T("search_all"));
    }

    void wire(){
        if(languageBadge!=null)languageBadge.setOnClickListener(v->showLanguageQuickMenu());
        if(planBadge!=null)planBadge.setOnClickListener(v->startActivity(new Intent(this,AccountActivity.class)));
        findViewById(R.id.menuButton).setOnClickListener(v->showNenoMenu());
        findViewById(R.id.profileButton).setOnClickListener(v->startActivityForResult(ProModuleInstaller.sourcesIntent(this),10));
        settingsButton.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        tvShareButton.setOnClickListener(v->showTvShareMenu());
        searchToggle.setOnClickListener(v->toggleSearch());
        findViewById(R.id.navHome).setOnClickListener(v->loadHome());findViewById(R.id.navLive).setOnClickListener(v->loadSection("live"));findViewById(R.id.navEpg).setOnClickListener(v->loadEpg());findViewById(R.id.navMovies).setOnClickListener(v->loadSection("vod"));findViewById(R.id.navSeries).setOnClickListener(v->loadSection("series"));
        genreButton.setOnClickListener(v->showGenreChooser());
        sortButton.setOnClickListener(v->showSortChooser());
        categories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?>p){}public void onItemSelected(AdapterView<?>p,View v,int pos,long id){if(settingCategories||search.getText().length()>0)return;Object o=p.getItemAtPosition(pos);if(!(o instanceof Category))return;Category c=(Category)o;autoDefaultGroup=false;if(c.id==null||c.id.isEmpty()){busy(false,T("choose_category"));return;}if(c.id.startsWith("lang:")){currentCategoryId=c.id;currentCategoryName=c.name;loadLanguageGroup(c.id.substring(5),false);return;}if("multi".equals(c.id)){currentCategoryId="multi";currentCategoryName="MULTI";loadLanguageGroup("multi",false);return;}if("other".equals(c.id)){currentCategoryId="other";currentCategoryName=T("other");loadOtherGroup(false);return;}if("all".equals(c.id)){currentCategoryId="all";currentCategoryName=T("all");if("epg".equals(section))loadEpgChannels("all");else loadItems("all");return;}currentCategoryId=c.id;currentCategoryName=c.name;if("epg".equals(section))loadEpgChannels(c.id);else loadItems(c.id);}});
        epgGridButton.setOnClickListener(v->setEpgMode(true));epgListButton.setOnClickListener(v->setEpgMode(false));
        list.setOnItemClickListener((p,v,pos,id)->{if("epg".equals(section)){showEpgDetails(epgAdapter.getItem(pos));return;}MediaEntry e=adapter.getItem(pos);if(selectedHero!=null&&selectedHero.uniqueKey().equals(e.uniqueKey()))select(e);else preview(e);});
        list.setOnItemLongClickListener((p,v,pos,id)->{MediaEntry e="epg".equals(section)?epgAdapter.getItem(pos):adapter.getItem(pos);actions(e);return true;});
        grid.setOnItemClickListener((p,v,pos,id)->{MediaEntry e=gridAdapter.getItem(pos);if(selectedHero!=null&&selectedHero.uniqueKey().equals(e.uniqueKey()))select(e);else preview(e);});
        grid.setOnItemLongClickListener((p,v,pos,id)->{actions(gridAdapter.getItem(pos));return true;});
        heroTitle.setOnLongClickListener(v->{if(selectedHero!=null)actions(selectedHero);return true;});
        grid.setOnItemLongClickListener((p,v,pos,id)->{actions(gridAdapter.getItem(pos));return true;});
        grid.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){}public void onScroll(AbsListView v,int first,int visible,int total){if(cachePagingActive&&!cachePageLoading&&visible>0&&total>0&&first+visible>=total-24)loadNextCachedPage(requestSerial,false);}});
        heroAction.setOnClickListener(v->{if(selectedHero!=null)select(selectedHero);});
        heroInfo.setOnClickListener(v->{if(selectedHero!=null)showDetails(selectedHero);});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int d){}public void onTextChanged(CharSequence s,int a,int b,int c){String q=s.toString();if(!q.trim().isEmpty()&&!"epg".equals(section)){categories.setVisibility(View.GONE);genreButton.setVisibility(View.GONE);showMediaGrid(false);gridAdapter.set(Collections.emptyList(),false);status.setText(T("searching"));}scheduleSearch(q);}public void afterTextChanged(Editable e){}});
    }

    void toggleSearch(){if(search.getVisibility()==View.VISIBLE){if(search.getText().length()>0)search.setText("");search.clearFocus();search.setVisibility(View.GONE);searchToggle.setText("⌕");((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}else{search.setVisibility(View.VISIBLE);searchToggle.setText("✕");search.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}}
    void showNenoMenu(){
    final Dialog d=new Dialog(this);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(14),dp(18),dp(22));
    android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xFF10141A);bg.setCornerRadii(new float[]{dp(22),dp(22),dp(22),dp(22),0,0,0,0});box.setBackground(bg);
    ImageView head=new ImageView(this);head.setImageResource(R.drawable.sunnyiptv_logo);head.setContentDescription(getString(R.string.app_name));head.setScaleType(ImageView.ScaleType.FIT_START);box.addView(head,new LinearLayout.LayoutParams(-1,dp(64)));
    EntitlementStore ent=new EntitlementStore(this);TextView plan=new TextView(this);plan.setText(ent.statusLabel(this));plan.setTextColor(0xFFA7AFBC);plan.setTextSize(13);plan.setPadding(0,0,0,dp(10));box.addView(plan);
    addNenoMenuItem(d,box,T("search_everywhere"),()->{toggleSearch();});
    addNenoMenuItem(d,box,T("account_and_pro"),()->startActivity(new Intent(this,AccountActivity.class)));
    addNenoMenuItem(d,box,T("household_profiles"),()->{if(ProGate.require(this,T("household_profiles")))startActivity(new Intent(this,HouseholdProfilesActivity.class));});
    addNenoMenuItem(d,box,T("manage_source"),()->startActivityForResult(ProModuleInstaller.sourcesIntent(this),10));
    addNenoMenuItem(d,box,T("casting"),()->{if(ProGate.require(this,T("casting")))showTvShareMenu();});
    addNenoMenuItem(d,box,SettingsStore.language(this).equals("nl")?"Verbindingstest":SettingsStore.language(this).equals("de")?"Verbindungstest":"Connection test",()->{if(ProGate.require(this,SettingsStore.language(this).equals("nl")?"Verbindingstest":SettingsStore.language(this).equals("de")?"Verbindungstest":"Connection test"))startActivity(ProModuleInstaller.networkIntent(this));});
    addNenoMenuItem(d,box,T("settings"),()->startActivity(new Intent(this,SettingsActivity.class)));
    addNenoMenuItem(d,box,FamilyUi.text(this,"Familiefilter","Family filter","Familienfilter"),()->startActivity(new Intent(this,FamilyActivity.class)));
    addNenoMenuItem(d,box,T("close"),()->{});
    d.setContentView(box);
    Window w=d.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setGravity(Gravity.BOTTOM);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(0.45f);}
    d.show();if(w!=null)w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
}
void addNenoMenuItem(Dialog d,LinearLayout box,String label,Runnable action){
    Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(0xFFF7F8FA);b.setTextSize(15);b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);b.setPadding(dp(14),0,dp(14),0);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1B2028));
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(7);box.addView(b,lp);b.setOnClickListener(v->{d.dismiss();action.run();});
}

    void showTvShareMenu(){
        final String[] options={T("screen_share"),T("google_cast")};
        new AlertDialog.Builder(this).setTitle(T("tv_share")).setItems(options,(d,which)->{if(which==0)openSystemScreenShare();else showMainCastChooser();}).setNegativeButton(T("cancel"),null).show();
    }
    void openSystemScreenShare(){
        Toast.makeText(this,T("screen_share_hint"),Toast.LENGTH_LONG).show();
        try{startActivity(new Intent(android.provider.Settings.ACTION_CAST_SETTINGS));return;}catch(Exception ignored){}
        try{startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));return;}catch(Exception ignored){}
        try{startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}catch(Exception e){Toast.makeText(this,T("screen_share_unavailable"),Toast.LENGTH_LONG).show();}
    }
    void showMainCastChooser(){
        try{MediaRouteSelector selector=new MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(NenoTVCastOptionsProvider.receiverApplicationId())).build();MediaRouteChooserDialog dialog=new MediaRouteChooserDialog(this);dialog.setRouteSelector(selector);dialog.show();}
        catch(Throwable e){Toast.makeText(this,T("cast_failed"),Toast.LENGTH_SHORT).show();}
    }
    void collapseSearch(){if(search.getText().length()==0){search.clearFocus();search.setVisibility(View.GONE);searchToggle.setText("⌕");}}
    public static void clearHeroCache(){HERO_CACHE.evictAll();}
    int heroHeight(){String h=SettingsStore.hero(this);int v="small".equals(h)?150:"large".equals(h)?195:165;return dp(v);}

    int nextRequest(){return ++requestSerial;} boolean current(int token){return token==requestSerial&&!isFinishing()&&!isDestroyed();}
    void pauseBackgroundIndexForUi(){
        if(delayedIndexResume!=null)ui.removeCallbacks(delayedIndexResume);
    }
void scheduleBackgroundIndex(){
    if(provider==null||profile==null||activityPaused)return;
    if(delayedIndexResume!=null)ui.removeCallbacks(delayedIndexResume);
    delayedIndexResume=()->{
        if(!isUiAlive()||provider==null||activityPaused)return;
        if(indexRefreshRunning){ui.postDelayed(delayedIndexResume,900);return;}
        refreshSearchIndex(false);
    };
    ui.postDelayed(delayedIndexResume,8000);
}

    void hideContentViews(){browseScroll.setVisibility(View.GONE);epgBoard.setVisibility(View.GONE);grid.setVisibility(View.GONE);list.setVisibility(View.GONE);}
    void showMediaList(){hideContentViews();list.setVisibility(View.VISIBLE);if(list.getAdapter()!=adapter)list.setAdapter(adapter);} void showEpgList(){hideContentViews();list.setVisibility(View.VISIBLE);if(list.getAdapter()!=epgAdapter)list.setAdapter(epgAdapter);} void showBrowse(){hideContentViews();browseScroll.setVisibility(View.VISIBLE);} void showMediaGrid(boolean live){hideContentViews();grid.setVisibility(View.VISIBLE);int sw=getResources().getConfiguration().screenWidthDp;grid.setNumColumns(live?(sw>=600?4:2):(sw>=600?6:3));}

    void updateBottomNav(String active){
    int on=0xFFFFD400,off=getResources().getColor(R.color.muted);
    int[] ids={R.id.navHome,R.id.navLive,R.id.navEpg,R.id.navMovies,R.id.navSeries};
    String[] keys={"home","live","epg","vod","series"};
    for(int i=0;i<ids.length;i++){Button b=findViewById(ids[i]);if(b==null)continue;boolean selected=keys[i].equals(active);b.setTextColor(selected?on:off);b.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);}
}

    void updateHeaderBadges(){
        if(languageBadge!=null)languageBadge.setText(SettingsStore.primaryLanguage(this).toUpperCase(Locale.ROOT));
        if(planBadge!=null){EntitlementStore e=new EntitlementStore(this);String b=DemoPolicy.isDemo(profiles==null?null:profiles.load())?"DEMO":e.shortBadge();if(e.isTrial())b=T("trial_badge")+" · "+e.trialDaysRemaining()+"d";planBadge.setText(b);boolean premium=e.isPro();planBadge.setTextColor(premium?0xFF0A0A0A:0xFFA7AFBC);planBadge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(premium?0xFFFFD400:0xFF1B2028));}
    }
    void showLanguageQuickMenu(){
        final String[] codes={"nl","en","de","fr","es","it","pt","tr","pl","ar"};
        String[] labels=new String[codes.length];for(int i=0;i<codes.length;i++)labels[i]=SettingsStore.displayLanguage(this,codes[i]);
        new AlertDialog.Builder(this).setTitle(T("language")).setItems(labels,(d,w)->{SettingsStore.setPrimaryLanguage(this,codes[w]);recreate();}).show();
    }

    void openProfile(){
        profileReady=false;
        Future<?> previousIndex=indexFuture;if(previousIndex!=null)previousIndex.cancel(true);
        int token=nextRequest();smartProviders.clear();smartMergeKey="";profile=profiles.load();title.setText("SunnyIPTV");setHeroDefault(profile.name==null||profile.name.trim().isEmpty()?T("welcome"):profile.name,T("connecting"));provider=newProvider(profile);latestSearchQuery="";busy(true,T("connecting"));
        final Provider connectingProvider=provider;
        exec.execute(()->{try{searchIndex.getWritableDatabase();migrateSearchIndexIfNeeded();
            boolean packaged=new com.nenotv.player.entitlement.CatalogPackageClient(this).bootstrap(searchIndex,profileKey(),profile,items->runOnUiThread(()->{if(current(token))busy(true,catalogText("importing")+" · "+items);}));
            if(!packaged)connectingProvider.authenticate();else if(profile.type==Profile.Type.M3U)provider=new CatalogM3uProvider(profile,SettingsStore.primaryLanguage(this),searchIndex,profileKey());String activeSource=new SourceStore(this).activeId();if(activeSource!=null&&!activeSource.isEmpty())smartProviders.put(activeSource,provider);runOnUiThread(()->{if(current(token)){profileReady=true;EpgRequests requests=epgRequests();adapter.setEpg(requests);gridAdapter.setEpg(requests);epgAdapter.configure(requests);openStart();autoReindexAfterConnect=false;ui.postDelayed(()->{if(provider!=null&&!indexRefreshRunning&&!isFinishing()&&!isDestroyed())refreshSearchIndex(false);},1500);}});}catch(com.nenotv.player.entitlement.CatalogPackageClient.Pending pending){runOnUiThread(()->{if(current(token)){busy(true,catalogText("preparing"));ui.postDelayed(()->{if(current(token)&&isUiAlive())openProfile();},10000);}});}catch(Exception e){runOnUiThread(()->{if(current(token)){busy(false,T("login_failed_prefix")+": "+friendly(e));new AlertDialog.Builder(this).setMessage(catalogText("failed")).setPositiveButton(catalogText("retry"),(d,w)->openProfile()).setNegativeButton(T("close"),null).show();}});}});
    }

    String catalogText(String key){
        String l=SettingsStore.language(this);
        if("preparing".equals(key))return "nl".equals(l)?"Mijn SunnyIPTV bereidt je mediapakket voor…":"de".equals(l)?"Mein SunnyIPTV bereitet dein Medienpaket vor…":"My SunnyIPTV is preparing your media package…";
        if("importing".equals(key))return "nl".equals(l)?"Mediapakket importeren":"de".equals(l)?"Medienpaket importieren":"Importing media package";
        if("retry".equals(key))return "nl".equals(l)?"Opnieuw proberen":"de".equals(l)?"Erneut versuchen":"Try again";
        return "nl".equals(l)?"Je bibliotheek kon niet worden geopend. Probeer opnieuw; je bestaande lijst blijft behouden.":"de".equals(l)?"Deine Bibliothek konnte nicht geöffnet werden. Versuche es erneut; die vorhandene Liste bleibt erhalten.":"Your library could not be opened. Try again; your existing list is preserved.";
    }

    void openStart(){String x=SettingsStore.startScreen(this);if("last".equals(x))x=SettingsStore.lastSection(this);if("live".equals(x))loadSection("live");else if("epg".equals(x))loadEpg();else if("vod".equals(x))loadSection("vod");else if("series".equals(x))loadSection("series");else loadHome();}

    void loadHome(){
        pauseBackgroundIndexForUi();stopCachePaging();nextRequest();hideSeasons();section="home";updateBottomNav("home");SettingsStore.setLastSection(this,"home");epgModeBar.setVisibility(View.GONE);setHeroHeight(heroHeight());seriesEpisodeMode=false;currentCategories.clear();genreButton.setVisibility(View.GONE);sortButton.setVisibility(View.GONE);categories.setVisibility(View.GONE);filterBar.setVisibility(View.GONE);collapseSearch();showBrowse();browseContainer.removeAllViews();setHeroDefault(T("watch_without_search"),T("one_place"));search.setHint(T("search_all"));
        List<MediaEntry>cont=visibleItems(library.continueWatching());List<MediaEntry>live=visibleItems(library.recent("live"));List<MediaEntry>favs=visibleItems(library.favorites());List<MediaEntry>local=new ArrayList<>();for(MediaEntry e:cont)if(!contains(local,e))local.add(e);for(MediaEntry e:live)if(!contains(local,e))local.add(e);for(MediaEntry e:favs)if(!contains(local,e))local.add(e);all=local;
        addShelf(T("continue"),cont);addShelf(T("recent_live"),live);addShelf(T("favorites"),favs);if(browseContainer.getChildCount()==0)addEmptyBrowse(T("empty_home"));if(!local.isEmpty())previewAuto(local.get(0));String lastCrash=CrashGuard.consumeLastType(this);busy(false,local.isEmpty()?T("watch_without_search"):"");warmCategories();scheduleBackgroundIndex();
    }
    void warmCategories(){if(provider==null||profile==null||profile.type!=Profile.Type.XTREAM)return;final String key=profileKey();for(String s:new String[]{"live","vod","series"})exec.execute(()->{try{List<Category> cached=searchIndex.cachedCategories(key,s);if(searchIndex.isFresh(key,s,SEARCH_INDEX_TTL_MS)||(!cached.isEmpty()&&searchIndex.categoriesFresh(key,s,30L*60L*1000L)))return;List<Category> c=new ArrayList<>(provider.categories(s));searchIndex.replaceCategories(key,s,c);}catch(Exception ignored){}});}
    boolean contains(List<MediaEntry>x,MediaEntry e){for(MediaEntry z:x)if(z.uniqueKey().equals(e.uniqueKey()))return true;return false;}

    void loadSection(String s){hideSeasons();
        pauseBackgroundIndexForUi();stopCachePaging();final int token=nextRequest();section=s;updateBottomNav(s);autoDefaultGroup=true;SettingsStore.setLastSection(this,s);seriesEpisodeMode=false;latestSearchQuery="";currentCategoryId="";currentCategoryName="";epgModeBar.setVisibility(View.GONE);if(profile!=null&&profile.type==Profile.Type.M3U&&!DemoPolicy.isDemo(profile)&&!s.equals("live")){showLocal(Collections.emptyList(),T("m3u_live_only"));return;}
        setHeroHeight(heroHeight());collapseSearch();search.setHint(T("search_everywhere"));filterBar.setVisibility(View.VISIBLE);sortButton.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);genreButton.setVisibility((s.equals("vod")||s.equals("series"))?View.VISIBLE:View.GONE);showMediaGrid(s.equals("live"));gridAdapter.set(Collections.emptyList(),s.equals("live"));setHeroDefault(label(s),s.equals("live")?T("all_live_sub"):s.equals("vod")?T("all_movies_sub"):T("all_series_sub"));busy(true,T("categories_loading"));
        exec.execute(()->{try{List<Category>loaded=searchIndex.cachedCategories(profileKey(),s);if(loaded.isEmpty()){loaded=new ArrayList<>(provider.categories(s));searchIndex.replaceCategories(profileKey(),s,loaded);}List<Category>raw=visibleCategories(loaded);runOnUiThread(()->{if(!current(token)||!section.equals(s))return;currentCategories=raw;setCategorySpinner(raw,true);String start=defaultGroupId(raw);selectSpinner(start);currentCategoryId=start;currentCategoryName=groupLabel(start);if(start.startsWith("lang:"))loadLanguageGroup(start.substring(5),false);else if("multi".equals(start))loadLanguageGroup("multi",false);else loadItems(start);scheduleBackgroundIndex();});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("error_prefix")+": "+friendly(e));});}});
    }

    void stopCachePaging(){cachePagingActive=false;cachePageLoading=false;cachePagingSection="";cachePagingTag="";cachePagingOffset=0;cachePagingTotal=0;}

    void loadCachedSection(String requested,int token,int cachedCount){
        stopCachePaging();final boolean live=requested.equals("live");cachePagingActive=true;cachePagingSection=requested;cachePagingTag="";cachePagingOffset=0;cachePagingTotal=cachedCount;all=new ArrayList<>();showMediaGrid(live);gridAdapter.set(Collections.emptyList(),live);busy(true,T("library_opening"));loadNextCachedPage(token,true);
    }
    void loadCachedLanguage(String requested,int token,String tag,int cachedCount){
        stopCachePaging();final boolean live=requested.equals("live");cachePagingActive=true;cachePagingSection=requested;cachePagingTag=tag;cachePagingOffset=0;cachePagingTotal=cachedCount;all=new ArrayList<>();showMediaGrid(live);gridAdapter.set(Collections.emptyList(),live);String label="multi".equals(tag)?"MULTI":SettingsStore.displayLanguage(this,tag);busy(true,label+" · "+T("library_opening"));loadNextCachedPage(token,true);
    }
    void loadCachedOther(String requested,int token,int cachedCount){
        stopCachePaging();final boolean live=requested.equals("live");cachePagingActive=true;cachePagingSection=requested;cachePagingTag="other";cachePagingOffset=0;cachePagingTotal=cachedCount;all=new ArrayList<>();showMediaGrid(live);gridAdapter.set(Collections.emptyList(),live);busy(true,T("other")+" · "+T("library_opening"));loadNextCachedPage(token,true);
    }

    void loadNextCachedPage(int token,boolean first){
        if(!cachePagingActive||cachePageLoading||!current(token)||!section.equals(cachePagingSection)||cachePagingOffset>=cachePagingTotal)return;
        cachePageLoading=true;final String requested=cachePagingSection;final int start=cachePagingOffset;final boolean live="live".equals(requested);final String sort=SettingsStore.sort(this);final String preferredLanguage=SettingsStore.contentLanguage(this);
        exec.execute(()->{
            try{
                List<MediaEntry>raw=cachePagingTag.isEmpty()?searchIndex.sectionPage(profileKey(),requested,start,CACHE_PAGE_SIZE,sort,preferredLanguage):("other".equals(cachePagingTag)?searchIndex.otherPage(profileKey(),requested,start,CACHE_PAGE_SIZE,sort):searchIndex.languagePage(profileKey(),requested,cachePagingTag,start,CACHE_PAGE_SIZE,sort));
                List<MediaEntry>page=visibleItems(raw);if(live)page=sortItems(page);final int latestTotal=cachePagingTag.isEmpty()?searchIndex.countSection(profileKey(),requested):("other".equals(cachePagingTag)?searchIndex.countOther(profileKey(),requested):searchIndex.countLanguage(profileKey(),requested,cachePagingTag));final int consumed=raw.size();final ArrayList<MediaEntry>push=new ArrayList<>(page);
                runOnUiThread(()->{
                    if(!current(token)||!cachePagingActive||!section.equals(requested)){cachePageLoading=false;return;}
                    cachePagingTotal=Math.max(latestTotal,start+consumed);cachePagingOffset=start+consumed;
                    if(!push.isEmpty()){if(smartMergeEnabled()&&cachePagingTag.isEmpty()){all=SmartSourceMerger.merge(all,push);gridAdapter.set(all,live);}else{all.addAll(push);gridAdapter.append(push);}if(selectedHero==null)previewAuto(push.get(0));}
                    if(first&&cachePagingTag.isEmpty())maybeMergeSecondarySources(requested,token);
                    cachePageLoading=false;busy(false,cachePagingTotal+" "+T("results"));
                    if(consumed==0||cachePagingOffset>=cachePagingTotal)cachePagingActive=false;
                });
            }catch(Throwable e){runOnUiThread(()->{cachePageLoading=false;if(current(token))busy(false,T("cache_label")+": "+friendlyThrowable(e));});}
        });
    }

    boolean hasLanguageCategories(List<Category>raw,String code){if(raw==null)return false;String tag=ContentLanguage.normalizeTag(code);if(tag.isEmpty())return false;for(Category x:raw)if(tag.equals(ContentLanguage.categoryTag(x.name)))return true;return false;}
    boolean hasMultiCategories(List<Category>raw){if(raw==null)return false;for(Category x:raw)if(ContentLanguage.categoryMulti(x.name))return true;return false;}
    boolean hasOtherCategories(List<Category>raw){if(raw==null)return false;for(Category x:raw)if(ContentLanguage.categoryTag(x.name).isEmpty())return true;return false;}
    String indexedSection(){return "epg".equals(section)?"live":section;}
    boolean canUseIndexedLanguage(){String s=indexedSection();return profile!=null&&(s.equals("live")||s.equals("vod")||s.equals("series"));}
    boolean hasIndexedLanguage(String tag){return false;}
    boolean hasLanguageGroup(List<Category>raw,String tag){return hasLanguageCategories(raw,tag);}
    boolean hasMultiGroup(List<Category>raw){return hasMultiCategories(raw);}
    boolean hasOtherGroup(List<Category>raw){return hasOtherCategories(raw);}
    List<Category> matchingLanguageCategories(String tag){List<Category>out=new ArrayList<>();String t=ContentLanguage.normalizeTag(tag);for(Category x:currentCategories){String ct=ContentLanguage.categoryTag(x.name);boolean ok="multi".equals(t)?"multi".equals(ct):t.equals(ct);if(ok)out.add(x);}out.sort((a,b)->safe(a.name).compareToIgnoreCase(safe(b.name)));return out;}
    List<Category> matchingOtherCategories(){List<Category>out=new ArrayList<>();for(Category x:currentCategories)if(ContentLanguage.categoryTag(x.name).isEmpty())out.add(x);out.sort((a,b)->safe(a.name).compareToIgnoreCase(safe(b.name)));return out;}
    String languageGroupLabel(String code){String n=SettingsStore.displayLanguage(this,code);if(n==null||n.trim().isEmpty())n=code.toUpperCase(Locale.ROOT);return n;}
    LinkedHashSet<String> availableLanguageTags(List<Category>raw){LinkedHashSet<String>tags=new LinkedHashSet<>();if(raw!=null)for(Category x:raw){String t=ContentLanguage.categoryTag(x.name);if(!t.isEmpty())tags.add(t);}tags.remove("");return tags;}
    static List<String> nameWords(String s){List<String>out=new ArrayList<>();for(String w:(s==null?"":s.toLowerCase(Locale.ROOT)).split("[^\\p{L}\\p{Nd}]+"))if(!w.isEmpty())out.add(w);return out;}
    static boolean isHdGroup(String name){return nameWords(name).contains("hd");}
    static boolean isCatchupGroup(String name){String n=name==null?"":name.toLowerCase(Locale.ROOT);if(n.contains("catch-up")||n.contains("catch up"))return true;for(String w:nameWords(name))if(w.equals("terugkijken")||w.equals("catchup")||w.equals("catch")||w.equals("replay")||w.equals("archief")||w.equals("archive"))return true;return false;}
    /** Provider groups of the viewer's language: HD first, then catch-up, then the rest alphabetically. */
    static int groupRank(String name){return isHdGroup(name)?0:isCatchupGroup(name)?1:2;}
    static List<Category> preferredGroups(List<Category>raw,String pref){List<Category>out=new ArrayList<>();String t=ContentLanguage.normalizeTag(pref);if(raw!=null)for(Category x:raw)if(x!=null&&x.id!=null&&!x.id.isEmpty()&&t.equals(ContentLanguage.categoryTag(x.name)))out.add(x);out.sort((a,b)->{int r=Integer.compare(groupRank(a.name),groupRank(b.name));return r!=0?r:DisplayText.category(a.name).compareToIgnoreCase(DisplayText.category(b.name));});return out;}
    String defaultGroupId(List<Category>raw){String preferred=SettingsStore.primaryLanguage(this);if(("live".equals(section)||"epg".equals(section))&&autoDefaultGroup){List<Category>g=preferredGroups(raw,preferred);if(!g.isEmpty()&&isHdGroup(g.get(0).name))return g.get(0).id;}return hasLanguageCategories(raw,preferred)?"lang:"+preferred:"all";}
    String groupLabel(String id){if(id==null)return T("all");if(id.startsWith("lang:"))return languageGroupLabel(id.substring(5));if("multi".equals(id))return "MULTI";if("other".equals(id))return T("other");if(!"all".equals(id)&&currentCategories!=null)for(Category x:currentCategories)if(x!=null&&id.equals(x.id))return x.name;return T("all");}
    void setCategorySpinner(List<Category>raw,boolean includeAll){
        final String pref=SettingsStore.primaryLanguage(this);LinkedHashSet<String>tags=availableLanguageTags(raw);List<Category>c=new ArrayList<>();
        tags.remove(pref);c.add(new Category("lang:"+pref,languageGroupLabel(pref),section));if("live".equals(section)||"epg".equals(section))for(Category x:preferredGroups(raw,pref))c.add(new Category(x.id,"\u00A0\u00A0\u00A0› "+x.name,section));
        if(tags.remove("multi"))c.add(new Category("multi","MULTI",section));
        ArrayList<String>others=new ArrayList<>();for(String t:tags)if(ContentLanguage.isLanguageTag(t))others.add(t);others.sort((a,b)->languageGroupLabel(a).compareToIgnoreCase(languageGroupLabel(b)));for(String t:others)c.add(new Category("lang:"+t,languageGroupLabel(t),section));
        if(hasOtherGroup(raw))c.add(new Category("other",T("other"),section));
        if(includeAll)c.add(new Category("all",T("all"),section));
        if(c.isEmpty())c.add(new Category("",T("choose_category"),section));
        ArrayAdapter<Category>a=new ArrayAdapter<Category>(this,android.R.layout.simple_spinner_item,c){@Override public View getView(int position,View convertView,ViewGroup parent){View v=super.getView(position,convertView,parent);if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(getResources().getColor(R.color.text));t.setPadding(24,10,24,10);Category item=getItem(position);if(item!=null)t.setText(item.toString().replace("\u00A0","").replace("› ","").trim()+"  ▾");t.setBackgroundColor(getResources().getColor(R.color.panel2));}return v;}@Override public View getDropDownView(int position,View convertView,ViewGroup parent){View v=super.getDropDownView(position,convertView,parent);if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(getResources().getColor(R.color.text));t.setBackgroundColor(getResources().getColor(R.color.card));t.setPadding(24,16,24,16);}return v;}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);settingCategories=true;categories.setAdapter(a);categories.setSelection(0,false);settingCategories=false;
    }
    void selectSpinner(String id){if(id==null)return;SpinnerAdapter a=categories.getAdapter();if(a==null)return;for(int i=0;i<a.getCount();i++){Object o=a.getItem(i);if(o instanceof Category&&id.equals(((Category)o).id)){settingCategories=true;categories.setSelection(i,false);settingCategories=false;return;}}}
    void confirmAll(){new AlertDialog.Builder(this).setTitle(T("large_library_title")).setMessage(T("large_library_msg")).setNegativeButton(T("cancel"),(d,w)->{settingCategories=true;categories.setSelection(0,false);settingCategories=false;}).setPositiveButton(T("load_anyway"),(d,w)->{if("epg".equals(section))loadEpgChannels("all");else loadItems("all");}).show();}

    void loadItems(String cat){
        final String requested=section;
        if("all".equals(cat)&&profile!=null&&(profile.type==Profile.Type.XTREAM||provider instanceof CatalogM3uProvider)&&(requested.equals("vod")||requested.equals("series")||requested.equals("live"))){
            final int token=nextRequest();final String key=profileKey();busy(true,T("library_opening"));
            exec.execute(()->{try{final int cached=searchIndex.countSection(key,requested);runOnUiThread(()->{if(!current(token)||!section.equals(requested))return;if(cached>0)loadCachedSection(requested,token,cached);else loadAllIncremental(requested);});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("loading_interrupted")+": "+friendly(e));});}});
            return;
        }
        loadCategoryItems(cat);
    }
    void loadCategoryItems(String cat){
        final String requested=section;if(!"all".equals(cat))stopCachePaging();
        final int token=nextRequest();busy(true,T("loading"));exec.execute(()->{try{List<MediaEntry>loaded=provider.items(requested,cat);try{searchIndex.upsert(profileKey(),loaded);}catch(Exception ignored){}List<MediaEntry>x=sortItems(visibleItems(loaded));runOnUiThread(()->{if(!current(token)||!section.equals(requested))return;all=x;if(requested.equals("vod")||requested.equals("series")||requested.equals("live")){showMediaGrid(requested.equals("live"));gridAdapter.set(x,requested.equals("live"));if(!x.isEmpty())previewAuto(x.get(0));}else{showMediaList();adapter.set(x);}busy(false,x.size()+" "+T("results")+(hiddenCount(loaded,x)>0?" · "+T("hidden_adult"):""));if("all".equals(cat))maybeMergeSecondarySources(requested,token);});}catch(Exception e){runOnUiThread(()->{if(current(token)&&!fallBackToLanguageGroup(cat))busy(false,T("error_prefix")+": "+friendly(e));});}});
    }

    void loadLanguageGroup(String tag,boolean fromRefresh){
        final String requested="epg".equals(section)?"live":section,expectedSection=section,key=profileKey(),sort=SettingsStore.sort(this);
        final String normalized=ContentLanguage.normalizeTag(tag);
        if(normalized.isEmpty()){if("epg".equals(section))loadEpgChannels("all");else loadItems("all");return;}
        stopCachePaging();final int token=nextRequest();final boolean epg="epg".equals(section);busy(true,T("library_opening"));
        exec.execute(()->{try{
            final int cached=searchIndex.countLanguage(key,requested,normalized);
            final List<MediaEntry> ready=epg&&cached>0?sortItems(visibleItems(searchIndex.languagePage(key,requested,normalized,0,Math.min(80,cached),sort))):Collections.emptyList();
            runOnUiThread(()->{if(current(token)&&section.equals(expectedSection))loadLanguageGroupWithCache(tag,fromRefresh,cached,ready);});
        }catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("loading_interrupted")+": "+friendly(e));});}});
    }
    void loadLanguageGroupWithCache(String tag,boolean fromRefresh,int cached,List<MediaEntry> cachedPage){
    final String requested="epg".equals(section)?"live":section;
    final String normalized=ContentLanguage.normalizeTag(tag);
    if(normalized.isEmpty()){if("epg".equals(section))loadEpgChannels("all");else loadItems("all");return;}
    stopCachePaging();final int token=nextRequest();final boolean epg="epg".equals(section);
    final String wantedId="multi".equals(normalized)?"multi":"lang:"+normalized;
    currentCategoryId=wantedId;currentCategoryName=groupLabel(wantedId);
    final List<Category>matches=matchingLanguageCategories(normalized);


    if(cached>0){
        if(epg){
            List<MediaEntry>x=cachedPage;
            all=x;showEpgByMode(x);busy(false,x.size()+" "+T("channels"));scheduleBackgroundIndex();
        }else{
            loadCachedLanguage(requested,token,normalized,cached);scheduleBackgroundIndex();
        }
        return;
    }

    if(epg){
        showEpgByMode(Collections.emptyList());busy(true,currentCategoryName+" · "+T("channels_loading"));
    }else{
        showMediaGrid("live".equals(requested));gridAdapter.set(Collections.emptyList(),"live".equals(requested));busy(true,T("library_opening"));
    }
    if(matches.isEmpty()){busy(false,T("no_results"));scheduleBackgroundIndex();return;}

    if(provider instanceof XtreamProvider&&!epg){
        refreshSearchIndex(false);
        return;
    }

    exec.execute(()->{
        try{
            List<MediaEntry>ready=Collections.emptyList();
            int attempts=Math.min(3,matches.size());
            for(int i=0;i<attempts&&!Thread.currentThread().isInterrupted();i++){
                Category cat=matches.get(i);
                List<MediaEntry>batch=provider.items(requested,cat.id);
                for(MediaEntry e:batch)if(e!=null)e.group=cat.name;
                try{searchIndex.upsert(profileKey(),batch);}catch(Exception ignored){}
                List<MediaEntry>visible=sortItems(visibleItems(batch));
                if(!visible.isEmpty()){ready=visible;break;}
            }
            final ArrayList<MediaEntry>shown=new ArrayList<>(ready);
            runOnUiThread(()->{
                if(!current(token)||!wantedId.equals(currentCategoryId))return;
                if(shown.isEmpty()){busy(true,T("first_sync_wait"));restoreFirstSyncBanner();}
                else if(epg){all=shown;showEpgByMode(shown);busy(false,shown.size()+" "+T("channels"));}
                else{all=new ArrayList<>(shown);showMediaGrid("live".equals(requested));gridAdapter.set(shown,"live".equals(requested));if(selectedHero==null)previewAuto(shown.get(0));busy(false,shown.size()+" "+T("results"));}
                scheduleBackgroundIndex();
            });
        }catch(Throwable ex){runOnUiThread(()->{if(current(token)){busy(false,T("error_prefix")+": "+friendlyThrowable(ex));scheduleBackgroundIndex();}});}
    });
}

    void loadOtherGroup(boolean fromRefresh){
        final String requested="epg".equals(section)?"live":section,expectedSection=section,key=profileKey(),sort=SettingsStore.sort(this);
        stopCachePaging();final int token=nextRequest();final boolean epg="epg".equals(section);busy(true,T("library_opening"));
        exec.execute(()->{try{
            final int cached=searchIndex.countOther(key,requested);
            final List<MediaEntry> ready=epg&&cached>0?sortItems(visibleItems(searchIndex.otherPage(key,requested,0,Math.min(250,cached),sort))):Collections.emptyList();
            runOnUiThread(()->{if(current(token)&&section.equals(expectedSection))loadOtherGroupWithCache(fromRefresh,cached,ready);});
        }catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("loading_interrupted")+": "+friendly(e));});}});
    }
    void loadOtherGroupWithCache(boolean fromRefresh,int cached,List<MediaEntry> ready){
        final String requested="epg".equals(section)?"live":section;final boolean epg="epg".equals(section);final int token=nextRequest();currentCategoryId="other";currentCategoryName=T("other");final List<Category>matches=matchingOtherCategories();
        if(epg&&cached>0){List<MediaEntry>x=ready;all=x;showEpgByMode(x);busy(false,x.size()+" "+T("channels")+" · "+T("other"));}
        else if(!epg&&cached>0)loadCachedOther(requested,token,cached);
        else if(epg){showEpgByMode(Collections.emptyList());busy(true,T("other")+" · "+T("channels_loading"));}
        else{showMediaGrid("live".equals(requested));gridAdapter.set(Collections.emptyList(),"live".equals(requested));busy(true,T("other")+" · "+T("library_opening"));}
        if(matches.isEmpty()){if(cached<=0)busy(false,T("no_results"));return;}
        if(provider instanceof XtreamProvider&&!epg){refreshSearchIndex(false);return;}
        exec.execute(()->{try{for(Category c:matches){if(!current(token)||Thread.currentThread().isInterrupted())break;try{List<MediaEntry>batch=provider.items(requested,c.id);for(MediaEntry e:batch)if(e!=null)e.group=c.name;searchIndex.upsert(profileKey(),batch);}catch(Exception ignored){}}final int count=searchIndex.countOther(profileKey(),requested);final List<MediaEntry> loadedPage=epg?sortItems(visibleItems(searchIndex.otherPage(profileKey(),requested,0,Math.min(250,count),SettingsStore.sort(this)))):Collections.emptyList();runOnUiThread(()->{if(!current(token)||!"other".equals(currentCategoryId))return;if(epg){List<MediaEntry>x=loadedPage;all=x;showEpgByMode(x);busy(false,x.size()+" "+T("channels")+" · "+T("other"));}else if(count>0)loadCachedOther(requested,token,count);else busy(false,T("no_results"));});}catch(Throwable ex){runOnUiThread(()->{if(current(token))busy(false,T("error_prefix")+": "+friendlyThrowable(ex));});}});
    }

    void loadAllIncremental(String requested){
        if(provider instanceof XtreamProvider){refreshSearchIndex(false);return;}
        final int token=nextRequest();fullLibraryToken=token;
        final String key=profileKey();final Provider source=provider;
        final List<Category> cats=new ArrayList<>(currentCategories);
        all=new ArrayList<>();showMediaGrid("live".equals(requested));gridAdapter.set(Collections.emptyList(),"live".equals(requested));busy(true,T("library_opening"));
        exec.execute(()->{
            final boolean[] published={false};final long[] lastPublish={0};
            try{
                for(Category c:cats){
                    if(!current(token)||Thread.currentThread().isInterrupted())break;
                    final boolean[] hasVisible={false};
                    XtreamProvider.BatchReceiver receive=batch->{
                        if(!current(token)||!key.equals(profileKey()))throw new java.io.InterruptedIOException("view_changed");
                        for(MediaEntry e:batch)e.group=c.name;
                        searchIndex.upsert(key,batch);
                        if(!visibleItems(batch).isEmpty())hasVisible[0]=true;
                        long now=android.os.SystemClock.elapsedRealtime();
                        if(hasVisible[0]&&(!published[0]||now-lastPublish[0]>=1000)){
                            final int count=searchIndex.countSection(key,requested);
                            final boolean first=!published[0];published[0]=true;lastPublish[0]=now;
                            runOnUiThread(()->{if(!current(token)||!requested.equals(section))return;if(first)loadCachedSection(requested,token,count);else{cachePagingTotal=count;cachePagingActive=cachePagingOffset<count;busy(false,count+" "+T("loaded"));}});
                        }
                    };
                    if(source instanceof XtreamProvider)((XtreamProvider)source).streamCategory(requested,c.id,receive);
                    else receive.accept(source.items(requested,c.id));
                    if(hasVisible[0])break;
                }
            }catch(Exception error){runOnUiThread(()->{if(current(token))busy(false,T("loading_interrupted"));});}
            finally{
                if(fullLibraryToken==token)fullLibraryToken=0;
                final int cached=searchIndex.countSection(key,requested);
                runOnUiThread(()->{if(!current(token)||!key.equals(profileKey()))return;if(!published[0])busy(false,T("index_building"));else{cachePagingTotal=cached;cachePagingActive=cachePagingOffset<cached;}refreshSearchIndex(false);});
            }
        });
    }

    void loadShowcase(String type,List<Category>cats,Category recommended){
        final int token=nextRequest();final Category newer=chooseNewCategory(cats,recommended);currentCategoryId=recommended.id;currentCategoryName=recommended.name;busy(true,T("for_you_loading"));
        exec.execute(()->{try{List<MediaEntry>recLoaded=provider.items(type,recommended.id);List<MediaEntry>freshLoaded=new ArrayList<>();if(newer!=null&&!newer.id.equals(recommended.id))try{freshLoaded=provider.items(type,newer.id);}catch(Exception ignored){}try{searchIndex.upsert(profileKey(),recLoaded);searchIndex.upsert(profileKey(),freshLoaded);}catch(Exception ignored){}List<MediaEntry>rec=sortItems(visibleItems(recLoaded));List<MediaEntry>fresh=sortItems(visibleItems(freshLoaded));runOnUiThread(()->{if(!current(token)||!section.equals(type))return;all=new ArrayList<>(rec);renderBrowse(type,rec,fresh);if(!rec.isEmpty())previewAuto(rec.get(0));busy(false,T("recommended"));});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("recommend_failed")+": "+friendly(e));});}});
    }
    Category chooseRecommendedCategory(String type,List<Category>cats){String preferred=library.preferredCategoryId(type);if(preferred!=null&&!preferred.isEmpty())for(Category c:cats)if(preferred.equals(c.id))return c;String[]priority={"new release","new releases","nieuw","latest","popular","populair","netflix","top"};for(String q:priority)for(Category c:cats)if(norm(c.name).contains(q))return c;return cats.get(0);}
    Category chooseNewCategory(List<Category>cats,Category fallback){String[]qs={"new release","new releases","nieuw","latest","recent","2026"};for(String q:qs)for(Category c:cats)if(norm(c.name).contains(q))return c;return fallback;}
    Category chooseLiveCategory(List<Category>cats){return cats==null||cats.isEmpty()?null:cats.get(0);}

    void renderBrowse(String type,List<MediaEntry>recommended,List<MediaEntry>fresh){showBrowse();browseContainer.removeAllViews();addShelf(T("continue"),sortItems(visibleItems(library.continueWatching(type))));addShelf(T("recommended"),recommended);addShelf(T("new_added"),fresh);addShelf(T("favorites"),sortItems(visibleItems(library.favorites(type))));if(browseContainer.getChildCount()==0)addEmptyBrowse(T("no_recommendations"));}
    void renderSingleShelf(String name,List<MediaEntry>items){List<MediaEntry>x=visibleItems(items);showMediaGrid(false);all=new ArrayList<>(x);gridAdapter.set(x,false);if(!x.isEmpty())grid.setSelection(0);}
    void addEmptyBrowse(String text){TextView t=new TextView(this);t.setText(text);t.setTextColor(getResources().getColor(R.color.muted));t.setTextSize(15);t.setPadding(dp(18),dp(24),dp(18),dp(24));browseContainer.addView(t);}
    void addShelf(String name,List<MediaEntry>rawItems){List<MediaEntry>items=visibleItems(rawItems);if(items==null||items.isEmpty())return;TextView head=new TextView(this);head.setText(name);head.setTextColor(getResources().getColor(R.color.text));head.setTextSize(15);head.setTypeface(null,Typeface.BOLD);head.setPadding(dp(13),dp(5),dp(8),dp(4));browseContainer.addView(head);HorizontalScrollView hsv=new HorizontalScrollView(this);hsv.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(9),0,dp(6),dp(5));hsv.addView(row,new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));int n=Math.min(20,items.size());for(int i=0;i<n;i++)row.addView(posterCard(items.get(i)));browseContainer.addView(hsv,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));}
    View posterCard(MediaEntry e){
        boolean live="live".equals(e.type),compact=SettingsStore.compact(this);int width=live?150:compact?108:128,height=live?84:compact?140:164;
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(4),dp(4),dp(4),dp(7));card.setBackgroundResource(R.drawable.bg_card_focus);card.setFocusable(true);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(width),-2);cp.setMargins(dp(4),0,dp(6),0);card.setLayoutParams(cp);
        FrameLayout art=new FrameLayout(this);ImageView image=new ImageView(this);art.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView star=new TextView(this);star.setText(library.isFavorite(e)?"★":"");star.setTextColor(getResources().getColor(R.color.accent));star.setTextSize(19);star.setPadding(dp(4),0,dp(4),0);art.addView(star,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT));card.addView(art,new LinearLayout.LayoutParams(-1,dp(height)));
        int percent=library.progressPercent(e);if(!live&&percent>0){ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress(percent);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.accent)));card.addView(bar,new LinearLayout.LayoutParams(-1,dp(4)));}
        TextView name=new TextView(this);name.setText(DisplayText.title(e));name.setTextColor(getResources().getColor(R.color.text));name.setTextSize(13);name.setTypeface(null,Typeface.BOLD);name.setMaxLines(3);name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(dp(4),dp(5),dp(4),0);card.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView meta=new TextView(this);meta.setTextColor(getResources().getColor(R.color.muted));meta.setTextSize(11);meta.setMaxLines(2);meta.setEllipsize(TextUtils.TruncateAt.END);meta.setPadding(dp(4),dp(3),dp(4),0);
        long remaining=Math.max(0,library.duration(e)-library.progress(e));String text=live?DisplayText.category(e.group):library.watched(e)?T("watched"):library.progress(e)>0&&remaining>0?Math.max(1,(remaining+59999)/60000)+" min · "+T("remaining"):DisplayText.shortMeta(e);meta.setText(text);card.addView(meta,new LinearLayout.LayoutParams(-1,-2));
        MediaRowAdapter.loadArtwork(image,e.logo,e.name,width*2,height*2);
        if(live&&provider!=null){final Provider asked=provider;final String key=profileKey();epgExec.execute(()->{try{List<EpgEntry> rows=epgRequests().load(e);EpgEntry now=EpgTimeline.now(rows,System.currentTimeMillis()/1000L);if(now!=null)runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))meta.setText(T("now")+": "+now.title);});}catch(Exception ignored){}});}
        card.setOnClickListener(v->{if(selectedHero!=null&&selectedHero.uniqueKey().equals(e.uniqueKey()))select(e);else preview(e);});card.setOnLongClickListener(v->{actions(e);return true;});return card;
    }


    void showGenreChooser(){if(!(section.equals("vod")||section.equals("series"))||currentCategories.isEmpty())return;String[]keys={"action","thriller","crime","scifi","fantasy","family","comedy","drama","horror","romance","documentary","animation"};String[]genres={T("genre_action"),T("genre_thriller"),T("genre_crime"),T("genre_scifi"),T("genre_fantasy"),T("genre_family"),T("genre_comedy"),T("genre_drama"),T("genre_horror"),T("genre_romance"),T("genre_documentary"),T("genre_animation")};String t=fullLibraryToken!=0?T("choose_genre_stop"):T("choose_genre");new AlertDialog.Builder(this).setTitle(t).setItems(genres,(d,w)->loadGenre(keys[w],genres[w])).show();}
    void loadGenre(String genreKey,String genreLabel){stopCachePaging();List<Category>matches=new ArrayList<>();List<String>terms=genreTerms(genreKey);for(Category c:currentCategories){String ct=ContentLanguage.categoryTag(c.name);if(currentCategoryId.startsWith("lang:")&&!currentCategoryId.substring(5).equals(ct))continue;if("multi".equals(currentCategoryId)&&!"multi".equals(ct))continue;if("other".equals(currentCategoryId)&&!ct.isEmpty())continue;String n=norm(c.name);for(String t:terms)if(n.contains(t)){matches.add(c);break;}}if(matches.isEmpty()){busy(false,T("no_genre_category")+" ‘"+genreLabel+"’");return;}final int token=nextRequest();busy(true,T("genre_loading")+" · "+genreLabel+"…");exec.execute(()->{try{LinkedHashMap<String,MediaEntry>dedup=new LinkedHashMap<>();int used=0;for(Category c:matches){if(used++>=4)break;for(MediaEntry e:provider.items(section,c.id)){dedup.putIfAbsent(e.uniqueKey(),e);if(dedup.size()>=180)break;}if(dedup.size()>=180)break;}List<MediaEntry>rawItems=new ArrayList<>(dedup.values());try{searchIndex.upsert(profileKey(),rawItems);}catch(Exception ignored){}List<MediaEntry>x=sortItems(visibleItems(rawItems));runOnUiThread(()->{if(!current(token))return;renderSingleShelf(T("genre")+" · "+genreLabel,x);if(!x.isEmpty())previewAuto(x.get(0));busy(false,x.size()+" "+T("titles")+" · "+genreLabel);});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("genre_failed")+": "+friendly(e));});}});}
    List<String> genreTerms(String g){if(g.equals("action"))return Arrays.asList("action","actie","accion","acción","violence","geweld","violencia","martial","war","oorlog","guerra");if(g.equals("crime"))return Arrays.asList("crime","misdaad","crimen","criminal");if(g.equals("scifi"))return Arrays.asList("sci fi","scifi","science fiction","sciencefiction","ciencia ficcion","ciencia ficción");if(g.equals("family"))return Arrays.asList("family","familie","familiar","kids","children","kinder");if(g.equals("comedy"))return Arrays.asList("comedy","komedie","comedia");if(g.equals("romance"))return Arrays.asList("romance","romantiek","romantic","romantico","romántico");if(g.equals("documentary"))return Arrays.asList("documentary","documentaire","documental","docu");if(g.equals("animation"))return Arrays.asList("animation","animatie","animacion","animación","animated","anime");if(g.equals("fantasy"))return Arrays.asList("fantasy","fantasie","fantasia","fantasía");if(g.equals("thriller"))return Arrays.asList("thriller","suspense");if(g.equals("drama"))return Arrays.asList("drama");if(g.equals("horror"))return Arrays.asList("horror","terror");return Collections.singletonList(norm(g));}
    String norm(String s){return safe(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9áéíóúàèëïöü]+"," ").replaceAll("\\s+"," ").trim();}

    void loadEpg(){hideSeasons();
        pauseBackgroundIndexForUi();stopCachePaging();final int token=nextRequest();section="epg";updateBottomNav("epg");autoDefaultGroup=true;SettingsStore.setLastSection(this,"epg");setHeroHeight(heroHeight());collapseSearch();seriesEpisodeMode=false;latestSearchQuery="";genreButton.setVisibility(View.GONE);sortButton.setVisibility(View.VISIBLE);filterBar.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);epgModeBar.setVisibility(View.VISIBLE);search.setHint(T("search_channel"));setHeroDefault(T("epg"),T("now_next"));gridAdapter.set(Collections.emptyList(),true);showEpgByMode(Collections.emptyList());busy(true,T("epg_categories_loading"));
        exec.execute(()->{try{List<Category>loaded=searchIndex.cachedCategories(profileKey(),"live");if(loaded.isEmpty()){loaded=new ArrayList<>(provider.categories("live"));searchIndex.replaceCategories(profileKey(),"live",loaded);}List<Category>raw=visibleCategories(loaded);runOnUiThread(()->{if(!current(token)||!section.equals("epg"))return;currentCategories=raw;setCategorySpinner(raw,true);if(raw.isEmpty()&&!FamilyStore.active(this)){busy(false,T("no_live_categories"));return;}String start=defaultGroupId(raw);selectSpinner(start);currentCategoryId=start;currentCategoryName=groupLabel(start);if(start.startsWith("lang:"))loadLanguageGroup(start.substring(5),false);else if("multi".equals(start))loadLanguageGroup("multi",false);else loadEpgChannels(start);scheduleBackgroundIndex();});}catch(Exception e){runOnUiThread(()->{if(current(token))busy(false,T("epg_error")+": "+friendly(e));});}});
    }
    void loadEpgChannels(String cat){final int token=nextRequest();busy(true,T("channels_loading"));exec.execute(()->{try{List<MediaEntry>x=sortItems(visibleItems(provider.items("live",cat)));runOnUiThread(()->{if(!current(token)||!section.equals("epg"))return;all=x;showEpgByMode(x);busy(false,x.size()+" "+T("channels"));});}catch(Exception e){runOnUiThread(()->{if(current(token)&&!fallBackToLanguageGroup(cat))busy(false,T("epg_channels_failed")+": "+friendly(e));});}});}
    /** When the automatically chosen HD group cannot be opened (e.g. offline), show the saved language list instead of an error. */
    boolean fallBackToLanguageGroup(String cat){if(!autoDefaultGroup||cat==null||!cat.equals(currentCategoryId)||!("live".equals(section)||"epg".equals(section)))return false;String p=SettingsStore.primaryLanguage(this);if(!hasLanguageCategories(currentCategories,p))return false;currentCategoryId="lang:"+p;currentCategoryName=groupLabel(currentCategoryId);selectSpinner(currentCategoryId);loadLanguageGroup(p,false);return true;}

    void setEpgMode(boolean gridMode){epgGridMode=gridMode;epgGridButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(gridMode?R.color.accent:R.color.panel2)));epgListButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getResources().getColor(gridMode?R.color.panel2:R.color.accent)));if("epg".equals(section))showEpgByMode(all);}
    void showEpgByMode(List<MediaEntry>channels){if(epgGridMode)renderEpgBoard(channels);else{showEpgList();epgAdapter.configure(epgRequests());epgAdapter.set(channels==null?Collections.emptyList():channels);}}
    void renderEpgBoard(List<MediaEntry>channels){
    epgRequestSerial++;try{epgExec.getQueue().clear();}catch(Exception ignored){}
    hideContentViews();epgBoard.setVisibility(View.VISIBLE);epgBoardContainer.removeAllViews();
    List<MediaEntry>src=new ArrayList<>(channels==null?Collections.emptyList():channels);
    src.sort((a,b)->{int ar=isRadioChannel(a)?1:0,br=isRadioChannel(b)?1:0;return ar!=br?Integer.compare(ar,br):0;});
    epgBaseEpoch=(System.currentTimeMillis()/1000L/1800L)*1800L;addEpgTimelineHeader();
    int cap=getResources().getConfiguration().screenWidthDp>=600?18:10;
    int n=Math.min(cap,src.size());for(int i=0;i<n;i++)addEpgBoardRow(src.get(i));
    if(src.size()>n){TextView more=new TextView(this);more.setText("+ "+(src.size()-n)+" "+T("channels")+" · "+T("list"));more.setTextColor(getResources().getColor(R.color.muted));more.setTextSize(12);more.setGravity(Gravity.CENTER);more.setPadding(dp(8),dp(14),dp(8),dp(18));more.setOnClickListener(v->setEpgMode(false));epgBoardContainer.addView(more);}
}

    boolean isRadioChannel(MediaEntry e){String x=(safe(e==null?"":e.group)+" "+safe(e==null?"":e.name)).toLowerCase(Locale.ROOT);return x.contains("radio")||x.matches(".*\\b(?:fm|dab)\\b.*");}
    int epgTimelineWidth(){int sw=getResources().getConfiguration().screenWidthDp;return Math.max(120,sw-132);}
    String clockAt(long epoch){try{return java.time.Instant.ofEpochSecond(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));}catch(Exception e){return "";}}
    void addEpgTimelineHeader(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);TextView blank=new TextView(this);blank.setText(T("now"));blank.setTextColor(getResources().getColor(R.color.muted));blank.setTextSize(10);blank.setGravity(Gravity.CENTER_VERTICAL);row.addView(blank,new LinearLayout.LayoutParams(dp(105),dp(28)));FrameLayout times=new FrameLayout(this);int w=epgTimelineWidth(),labelW=44;for(int i=0;i<=4;i++){TextView t=new TextView(this);t.setText(clockAt(epgBaseEpoch+i*1800L));t.setTextColor(getResources().getColor(R.color.muted));t.setTextSize(9);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(labelW),dp(28));lp.leftMargin=dp(Math.max(0,(w-labelW)*i/4));t.setLayoutParams(lp);times.addView(t);}row.addView(times,new LinearLayout.LayoutParams(dp(w),dp(28)));epgBoardContainer.addView(row);}
    void addEpgBoardRow(MediaEntry ch){
    LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(3),dp(2),dp(3),dp(2));
    TextView name=new TextView(this);name.setText(DisplayText.title(ch));name.setTextColor(getResources().getColor(R.color.text));name.setTextSize(10.5f);name.setTypeface(null,Typeface.BOLD);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER_VERTICAL);row.addView(name,new LinearLayout.LayoutParams(dp(105),dp(66)));
    FrameLayout timeline=new FrameLayout(this);timeline.setBackgroundColor(0xFF10141C);int timelineDp=epgTimelineWidth();row.addView(timeline,new LinearLayout.LayoutParams(dp(timelineDp),dp(66)));row.setOnClickListener(v->play(ch));epgBoardContainer.addView(row,new LinearLayout.LayoutParams(-1,dp(70)));
    TextView loading=epgTimelineCard(T("epg_loading"),false);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-1);loading.setLayoutParams(lp);timeline.addView(loading);
    final long base=epgBaseEpoch;final int request=epgRequestSerial;
    epgExec.execute(()->{try{
        if(request!=epgRequestSerial)return;
        List<EpgEntry>events=epgRequests().load(ch);
        runOnUiThread(()->{
            if(!isUiAlive()||!"epg".equals(section)||base!=epgBaseEpoch||request!=epgRequestSerial)return;
            timeline.removeAllViews();long horizon=base+7200L;int added=0;
            for(EpgEntry e:events){long st=e.startEpoch>0?e.startEpoch:base,en=e.endEpoch>st?e.endEpoch:st+1800L;if(en<=base||st>=horizon)continue;long visStart=Math.max(base,st),visEnd=Math.min(horizon,en);int left=(int)Math.round((visStart-base)*timelineDp/7200.0),width=Math.max(1,Math.min(timelineDp-left,(int)Math.round((visEnd-visStart)*timelineDp/7200.0)));String range=e.range();String visibleRange=width<Math.round(120*getResources().getConfiguration().fontScale)?range.replace("–","\n"):range;TextView card=epgTimelineCard((visibleRange.isEmpty()?"":visibleRange+"\n")+e.title,e.isNow());card.setContentDescription((range.isEmpty()?"":range+" · ")+e.title);card.setOnClickListener(v->showEpgDetails(ch));if(width<64){card.setSingleLine(true);card.setText(e.title);card.setPadding(dp(2),dp(5),dp(2),dp(3));}FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(width),dp(62));cp.leftMargin=dp(left);cp.topMargin=dp(2);card.setLayoutParams(cp);timeline.addView(card);added++;}
            if(added==0){TextView none=epgTimelineCard(T("no_epg"),false);FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,-1);none.setLayoutParams(np);timeline.addView(none);}
        });
    }catch(Exception ignored){runOnUiThread(()->{if(isUiAlive()&&"epg".equals(section)&&request==epgRequestSerial){timeline.removeAllViews();TextView none=epgTimelineCard(T("no_epg"),false);FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,-1);none.setLayoutParams(np);timeline.addView(none);}});}});
}

    TextView epgTimelineCard(String text,boolean now){TextView v=new TextView(this);v.setText(text);v.setTextColor(now?0xFF0A0A0A:0xFFF7F8FA);v.setTextSize(11f);v.setMaxLines(3);v.setEllipsize(TextUtils.TruncateAt.END);v.setPadding(dp(6),dp(5),dp(5),dp(3));v.setGravity(Gravity.CENTER_VERTICAL);v.setBackgroundColor(now?getResources().getColor(R.color.accent):0xFF1A1F29);return v;}

    void showEpgDetails(MediaEntry channel){
        if(isAdultLocked(channel)){FamilyUi.blocked(this);return;}
        if(channel==null)return;busy(true,T("guide_loading"));
        exec.execute(()->{try{List<EpgEntry> rows=epgRequests().load(channel);runOnUiThread(()->{if(!isUiAlive())return;busy(false,T("epg"));StringBuilder m=new StringBuilder();for(EpgEntry e:rows){if(m.length()>0)m.append("\n\n");m.append(e.isNow()?T("now")+" · ":"").append(e.range());if(!e.range().isEmpty())m.append("\n");m.append(e.title);if(e.description!=null&&!e.description.trim().isEmpty())m.append("\n").append(e.description);}AlertDialog dlg=new AlertDialog.Builder(this).setTitle(DisplayText.title(channel)).setMessage(m.length()==0?T("no_epg"):m.toString()).setPositiveButton(T("watch"),(d,w)->play(channel)).setNegativeButton(T("close"),null).show();if(m.length()>0){String raw=m.toString();InfoTranslator.translate(raw,SettingsStore.primaryLanguage(this),translated->runOnUiThread(()->{if(isUiAlive()&&dlg.isShowing())dlg.setMessage(translated);}));}});}catch(Exception ex){runOnUiThread(()->busy(false,T("epg_error")+": "+friendly(ex)));}});
    }

    void scheduleSearch(String q){if(pendingSearch!=null)ui.removeCallbacks(pendingSearch);final String asked=q==null?"":q;pendingSearch=()->searchEverywhere(asked);ui.postDelayed(pendingSearch,160);}
    void searchEverywhere(String q){String z=q==null?"":q.trim().toLowerCase(Locale.ROOT);latestSearchQuery=z;if("epg".equals(section)){filterBar.setVisibility(z.isEmpty()?View.VISIBLE:View.GONE);if(z.isEmpty())showEpgByMode(all);else{showEpgList();epgAdapter.configure(epgRequests());epgAdapter.set(all);epgAdapter.filter(z);}busy(false,z.isEmpty()?T("epg"):T("results"));return;}if(z.isEmpty()){if(section.equals("home")||section.equals("local"))loadHome();else if(section.equals("vod")||section.equals("series"))loadSection(section);else if(section.equals("live")){filterBar.setVisibility(View.VISIBLE);categories.setVisibility(View.VISIBLE);genreButton.setVisibility(View.GONE);showMediaGrid(true);gridAdapter.set(new ArrayList<>(all),true);busy(false,all.size()+" "+T("results"));}return;}if(z.length()<2){gridAdapter.set(Collections.emptyList(),false);status.setText(T("type_2"));return;}categories.setVisibility(View.GONE);genreButton.setVisibility(View.GONE);filterBar.setVisibility(View.GONE);showMediaGrid(false);final int token=nextRequest();final String query=z;gridAdapter.set(Collections.emptyList(),false);status.setText(T("searching"));exec.execute(()->{try{String sec=(section.equals("live")||section.equals("vod")||section.equals("series"))?section:"";String tag=currentCategoryId.startsWith("lang:")?currentCategoryId.substring(5):("multi".equals(currentCategoryId)?"multi":("other".equals(currentCategoryId)?"other":""));List<MediaEntry>x=sortItems(smartDedup(visibleItems(searchIndex.searchFiltered(profileKey(),sec,query,tag,1000))));final int indexed=searchIndex.count(profileKey());runOnUiThread(()->{if(!current(token)||!query.equals(latestSearchQuery))return;gridAdapter.set(x,false);if(x.isEmpty()&&indexed==0){if(!indexRefreshRunning)refreshSearchIndex(false);status.setText(T("index_building"));}else busy(false,x.isEmpty()?T("no_results_for")+" ‘"+query+"’":x.size()+" "+T("results"));});}catch(Exception e){runOnUiThread(()->{if(current(token)&&query.equals(latestSearchQuery))busy(false,T("local_search_failed")+": "+friendly(e));});}});}
    Provider newProvider(Profile p){return newProvider(p,SettingsStore.primaryLanguage(this));}
    Provider newProvider(Profile p,String language){return p.type==Profile.Type.XTREAM?new XtreamProvider(p):new M3uProvider(p,language);}
    synchronized Provider providerFor(MediaEntry e)throws Exception{
        return sourceResolver().resolve(e,provider,ProLibraryBridge.isActive(this));
    }
    private synchronized SourceProviderResolver sourceResolver(){
        if(sourceResolver==null)sourceResolver=new SourceProviderResolver(this,this::newProvider);
        return sourceResolver;
    }
    private EpgRequests epgRequests(){try{return new EpgRequests(epgStore,provider,profileKey()+"|"+SourceProviderResolver.profileNamespace(profile),sourceResolver(),()->ProLibraryBridge.isActive(this));}catch(Exception failure){throw new IllegalStateException("EPG_CONTEXT_FAILED",failure);}}
    String epgProfileKey(MediaEntry e){return profileKey()+(e!=null&&e.sourceId!=null&&!e.sourceId.isEmpty()?"|"+e.sourceId:"");}
    boolean smartMergeEnabled(){return ProLibraryBridge.isActive(this)&&SettingsStore.prefs(this).getBoolean("pro_smart_merge",false);}
    List<MediaEntry> smartDedup(List<MediaEntry> in){return smartMergeEnabled()?SmartSourceMerger.merge(Collections.emptyList(),in):in;}
    void maybeMergeSecondarySources(String requested,int token){
        if(!smartMergeEnabled()||!("live".equals(requested)||"vod".equals(requested)||"series".equals(requested)))return;
        String mergeKey=token+"|"+requested+"|"+profileKey();
        if(mergeKey.equals(smartMergeKey))return;smartMergeKey=mergeKey;
        final SourceStore registry=new SourceStore(this);final String activeId=registry.activeId();final List<SourceStore.Entry> entries=registry.list();
        exec.execute(()->{
            ArrayList<MediaEntry> secondary=new ArrayList<>();
            for(SourceStore.Entry entry:entries){
                if(!current(token)||Thread.currentThread().isInterrupted())return;
                if(entry==null||!entry.enabled||entry.id.equals(activeId))continue;
                try{
                    MediaEntry source=new MediaEntry();source.sourceId=entry.id;
                    Provider p=providerFor(source);
                    List<MediaEntry> rows=p.items(requested,"all");SmartSourceMerger.tag(rows,entry.id,entry.profile.name);
                    secondary=new ArrayList<>(SmartSourceMerger.merge(secondary,rows));
                }catch(Exception ignored){}
            }
            if(secondary.isEmpty())return;
            try{searchIndex.upsert(profileKey(),secondary);}catch(Exception ignored){}
            final ArrayList<MediaEntry> additions=secondary;
            runOnUiThread(()->{
                if(!current(token)||!section.equals(requested)||!smartMergeEnabled())return;
                all=sortItems(visibleItems(SmartSourceMerger.merge(all,additions)));
                showMediaGrid("live".equals(requested));gridAdapter.set(all,"live".equals(requested));
                busy(false,all.size()+" "+T("results")+" · PRO Smart Sources");
            });
        });
    }

    String safe(String s){return s==null?"":s;} String profileKey(){return ProfileCacheKey.of(profile);} String cacheCursorKey(String type){return cacheCursorKey(profileKey(),type);} String cacheCursorKey(String key,String type){return "cache_cursor_"+key+"_"+type;}
    void publishIndexedTop(String type){
        publishIndexedTop(type,false);
    }
    void publishIndexedTop(String type,boolean finished){
        long now=android.os.SystemClock.elapsedRealtime();if(!finished&&now-lastIndexUiPublish<1200)return;lastIndexUiPublish=now;
        final String key=profileKey(),sort=SettingsStore.sort(this),pref=SettingsStore.contentLanguage(this),expected=currentCategoryId;
        int total=searchIndex.countSection(key,type);if(total<=0)return;
        List<MediaEntry>ready;
        if(expected.startsWith("lang:")||"multi".equals(expected)){
            String tag=expected.startsWith("lang:")?expected.substring(5):"multi";
            total=searchIndex.countLanguage(key,type,tag);ready=visibleItems(searchIndex.languagePage(key,type,tag,0,CACHE_PAGE_SIZE,sort));
        }else if("other".equals(expected)){
            total=searchIndex.countOther(key,type);ready=visibleItems(searchIndex.otherPage(key,type,0,CACHE_PAGE_SIZE,sort));
        }else if("all".equals(expected))ready=visibleItems(searchIndex.sectionPage(key,type,0,CACHE_PAGE_SIZE,sort,pref));
        else return;
        if(ready.isEmpty())return;final ArrayList<MediaEntry>shown=new ArrayList<>(ready);final int shownTotal=total;
        runOnUiThread(()->{if(!isUiAlive()||!key.equals(profileKey())||!type.equals(section)||(latestSearchQuery!=null&&!latestSearchQuery.isEmpty())||currentCategories.isEmpty())return;if(!expected.equals(currentCategoryId))return;if(grid.getVisibility()!=View.VISIBLE)return;if(grid.getFirstVisiblePosition()>2){if(type.equals(cachePagingSection)){cachePagingTotal=shownTotal;cachePagingActive=cachePagingOffset<shownTotal;}return;}stopCachePaging();all=new ArrayList<>(shown);cachePagingSection=type;cachePagingTag=expected.startsWith("lang:")?expected.substring(5):"all".equals(expected)?"":expected;cachePagingOffset=Math.min(CACHE_PAGE_SIZE,shownTotal);cachePagingTotal=shownTotal;cachePagingActive=cachePagingOffset<shownTotal;showMediaGrid("live".equals(type));gridAdapter.set(shown,"live".equals(type));MediaEntry first=shown.get(0);if(selectedHero==null||selectedHero.uniqueKey().equals(indexAutoHeroKey)){previewAuto(first);indexAutoHeroKey=first.uniqueKey();}busy(false,shownTotal+" "+T("results"));});
    }

    void reloadIndexedSectionWhenReady(String type){
        runOnUiThread(()->{
            if(!isUiAlive()||!type.equals(section)||(latestSearchQuery!=null&&!latestSearchQuery.isEmpty()))return;String preferred="lang:"+SettingsStore.primaryLanguage(this);if(!autoDefaultGroup&&!preferred.equals(currentCategoryId))return;
            indexAutoHeroKey="";loadSection(type);
        });
    }

    synchronized void refreshSearchIndex(boolean force){
        if(provider==null||profile==null)return;if(indexRefreshRunning){if(force)indexRefreshRequested=true;return;}
        indexRefreshRunning=true;final Provider indexProvider=provider;final String key=profileKey();final boolean requestedForce=force;
        indexFuture=indexExec.submit(()->{
            boolean allComplete=false;
            try{
                android.content.SharedPreferences sp=SettingsStore.prefs(this);
                final int schema=4;
                boolean migration=sp.getInt("language_index_version_"+key,0)<schema;
                if(migration&&!sp.getBoolean("language_index_rebuild_started_"+key,false)){
                    sp.edit().putBoolean("language_index_rebuild_started_"+key,true).putBoolean("first_sync_done_"+key,false)
                      .remove(cacheCursorKey(key,"vod")).remove(cacheCursorKey(key,"series")).remove(cacheCursorKey(key,"live"))
                      .putInt("first_sync_done_count_"+key,0).putInt("first_sync_total_count_"+key,0).putInt("first_sync_titles_"+key,0).apply();
                }
                if(indexProvider instanceof M3uProvider){
                    if(requestedForce||!searchIndex.isFresh(key,"live",SEARCH_INDEX_TTL_MS)){
                        indexProvider.authenticate();
                        if(DemoPolicy.isDemo(profile)){
                            for(String type:new String[]{"live","vod","series"}){
                                searchIndex.replaceSection(key,type,indexProvider.items(type,"all"));
                                searchIndex.replaceCategories(key,type,indexProvider.categories(type));
                            }
                        }else{
                            List<MediaEntry>x=indexProvider.items("live","all");if(x.isEmpty())throw new IllegalStateException("EMPTY_PLAYLIST");searchIndex.replaceSection(key,"live",x);
                            searchIndex.replaceCategories(key,"live",indexProvider.categories("live"));
                        }
                    }
                    allComplete=true;
                }else{
                    final String[] baseTypes={"live","vod","series"};
                    ArrayList<String> order=new ArrayList<>();
                    if(section.equals("live")||section.equals("vod")||section.equals("series"))order.add(section);
                    for(String t:baseTypes)if(!order.contains(t))order.add(t);

                    LinkedHashMap<String,List<Category>> catMap=new LinkedHashMap<>();
                    int globalTotal=0;
                    final String preferred=SettingsStore.contentLanguage(this);
                    for(String type:baseTypes){
                        List<Category> cats=searchIndex.cachedCategories(key,type);
                        catMap.put(type,cats);globalTotal+=cats.size();
                    }

                    int globalDone=0;
                    for(String type:baseTypes){
                        List<Category> cats=catMap.get(type);
                        if(!migration&&!requestedForce&&searchIndex.isFresh(key,type,SEARCH_INDEX_TTL_MS)){globalDone+=cats.size();continue;}
                        String cursor=SettingsStore.prefs(this).getString(cacheCursorKey(key,type),"");
                        int startAt=0;
                        if(cursor!=null&&!cursor.isEmpty()){
                            for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}
                            if(startAt>=cats.size())startAt=0;
                        }
                        globalDone+=startAt;
                    }

                    int grandTotal=Math.max(1,globalTotal);
                    int estimatedTitles=searchIndex.count(key);
                    final int initialDone=globalDone,initialTitles=estimatedTitles,initialTotal=grandTotal;
                    if(isUiAlive())runOnUiThread(()->showIndexBanner("",initialDone,initialTotal,initialTitles));

                    for(String type:order){
                        if(Thread.currentThread().isInterrupted())break;
                        if(!migration&&!requestedForce&&searchIndex.isFresh(key,type,SEARCH_INDEX_TTL_MS))continue;
                        List<Category> cats=catMap.get(type);
                        boolean categoryCacheFresh=searchIndex.categoriesFresh(key,type,30L*60L*1000L);
                        if(cats==null)cats=new ArrayList<>();
                        if(cats.isEmpty()||requestedForce||!categoryCacheFresh){
                            int previousCategoryCount=cats.size();
                            try{
                                List<Category> remoteCats=new ArrayList<>(indexProvider.categories(type));
                                searchIndex.replaceCategories(key,type,remoteCats);
                                cats=remoteCats;
                                catMap.put(type,cats);
                                grandTotal=Math.max(1,grandTotal+(cats.size()-previousCategoryCount));
                                SettingsStore.prefs(this).edit().putInt("first_sync_total_count_"+key,grandTotal).apply();
                                final int discoveredTotal=grandTotal,discoveredDone=globalDone,discoveredTitles=searchIndex.count(key);
                                runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))showIndexBanner("",discoveredDone,discoveredTotal,discoveredTitles);});
                            }catch(Exception categoryError){
                                if(cats.isEmpty())throw categoryError;
                            }
                        }

                        int startAt=0;
                        String cursor=requestedForce?"":SettingsStore.prefs(this).getString(cacheCursorKey(key,type),"");
                        if(cursor!=null&&!cursor.isEmpty()){
                            for(int ci=0;ci<cats.size();ci++)if(cursor.equals(cats.get(ci).id)){startAt=ci+1;break;}
                            if(startAt>=cats.size())startAt=0;
                        }

                        // Fast path: standard Xtream servers can return a complete section in one request.
                        // Preserve category names locally; fall back to bounded per-category fetches if bulk fails.
                        boolean bulkDone=false;
                        SearchIndexStore.ImportProgress resumeProgress=searchIndex.importProgress(key,type);
                        boolean resumeCategories=!requestedForce&&resumeProgress!=null&&!safe(resumeProgress.cursor).isEmpty();
                        if(indexProvider instanceof XtreamProvider&&!resumeCategories&&!Thread.currentThread().isInterrupted()){
                            final XtreamProvider importProvider=(XtreamProvider)indexProvider;
                            String session=null;
                            try{
                                waitWhilePaused();waitForLibraryLoad();
                                com.nenotv.player.net.StreamingJsonArray.checkCancelled();
                                final String importSession=searchIndex.beginSectionImport();session=importSession;
                                HashMap<String,String> groupNames=new HashMap<>();
                                for(Category c:cats)groupNames.put(safe(c.id),c.name);
                                final int[] received={0};final long[] lastPublish={0};
                                final boolean publishPartial=!searchIndex.isComplete(key,type);
                                importProvider.streamSection(type,batch->{
                                    waitWhilePaused();waitForLibraryLoad();
                                    com.nenotv.player.net.StreamingJsonArray.checkCancelled();
                                    for(MediaEntry e:batch){String g=groupNames.get(safe(e.categoryId));if(g!=null)e.group=g;}
                                    searchIndex.importBatch(importSession,key,type,batch,publishPartial);
                                    received[0]+=batch.size();
                                    long now=android.os.SystemClock.elapsedRealtime();
                                    if(now-lastPublish[0]>=1000){
                                        lastPublish[0]=now;final int loaded=received[0];
                                        searchIndex.checkpointImport(key,type,importSession,"",loaded);
                                        SettingsStore.prefs(this).edit().putInt("first_sync_titles_"+key,searchIndex.count(key)).apply();
                                        runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))showStreamingBanner(type,loaded);});
                                        if(publishPartial&&key.equals(profileKey()))publishIndexedTop(type);
                                    }
                                });
                                searchIndex.finishSectionImport(importSession,key,type);session=null;
                                searchIndex.clearImportProgress(key,type);
                                SettingsStore.prefs(this).edit().remove(cacheCursorKey(key,type)).apply();
                                globalDone+=Math.max(0,cats.size()-startAt);
                                estimatedTitles=searchIndex.count(key);
                                final int gd=globalDone,gt=grandTotal,ti=estimatedTitles;
                                runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))showIndexBanner("",gd,gt,ti);});
                                if(key.equals(profileKey())){publishIndexedTop(type,true);reloadIndexedSectionWhenReady(type);}bulkDone=true;
                            }catch(Exception bulkError){
                                android.util.Log.w("NenoTVImport","Bulk import failed for "+type+": "+bulkError.getClass().getSimpleName());
                                if(session!=null){try{searchIndex.abortSectionImport(session);}catch(Exception cleanupError){bulkError.addSuppressed(cleanupError);}}
                                if(Thread.currentThread().isInterrupted())break;
                            }
                        }
                        if(bulkDone)continue;


                        SearchIndexStore.ImportProgress progress=searchIndex.importProgress(key,type);
                        String fallbackSession=searchIndex.beginSectionImport(progress==null?null:progress.session);
                        try{
                            final String importSession=fallbackSession;
                            final boolean publishPartial=!searchIndex.isComplete(key,type);
                            int resumeAt=startAt;
                            String resumeCursor=progress==null?"":safe(progress.cursor);
                            if(!resumeCursor.isEmpty()){for(int ci=0;ci<cats.size();ci++)if(resumeCursor.equals(cats.get(ci).id)){resumeAt=Math.max(resumeAt,ci+1);break;}}
                            final int categoryWindowSize=2;
                            ExecutorService categoryPool=Executors.newFixedThreadPool(categoryWindowSize);
                            CompletionService<Integer> categoryResults=new ExecutorCompletionService<>(categoryPool);
                            CategoryCompletionWindow completionWindow=new CategoryCompletionWindow(cats.size(),resumeAt);
                            int nextCategory=resumeAt;
                            int inFlight=0;
                            try{
                                while(nextCategory<cats.size()&&inFlight<categoryWindowSize){
                                    final int categoryIndex=nextCategory++;
                                    final Category category=cats.get(categoryIndex);
                                    categoryResults.submit(()->{
                                        waitWhilePaused();waitForLibraryLoad();
                                        com.nenotv.player.net.StreamingJsonArray.checkCancelled();
                                        ((XtreamProvider)indexProvider).streamCategory(type,category.id,batch->{
                                            waitWhilePaused();waitForLibraryLoad();
                                            for(MediaEntry e:batch)e.group=category.name;
                                            searchIndex.importBatch(importSession,key,type,batch,publishPartial);
                                        });
                                        return categoryIndex;
                                    });
                                    inFlight++;
                                }
                                while(inFlight>0){
                                    Future<Integer> completedFuture=categoryResults.take();
                                    int completedIndex;
                                    try{completedIndex=completedFuture.get();}
                                    catch(ExecutionException failed){
                                        Throwable cause=failed.getCause();
                                        if(cause instanceof Exception)throw (Exception)cause;
                                        throw new RuntimeException(cause);
                                    }
                                    inFlight--;
                                    globalDone++;
                                    int committedIndex=completionWindow.markDone(completedIndex);
                                    int staged=searchIndex.importCount(importSession,key,type);
                                    android.content.SharedPreferences.Editor progressEdit=SettingsStore.prefs(this).edit()
                                        .putInt("first_sync_done_count_"+key,globalDone)
                                        .putInt("first_sync_titles_"+key,searchIndex.count(key));
                                    if(committedIndex>=resumeAt){
                                        String committedCategoryId=cats.get(committedIndex).id;
                                        searchIndex.checkpointImport(key,type,importSession,committedCategoryId,staged);
                                        progressEdit.putString(cacheCursorKey(key,type),committedCategoryId);
                                    }else{
                                        searchIndex.checkpointImport(key,type,importSession,"",staged);
                                    }
                                    progressEdit.apply();
                                    if(publishPartial&&key.equals(profileKey()))publishIndexedTop(type);
                                    final int gd=globalDone,gt=grandTotal,ti=searchIndex.count(key);
                                    runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))showIndexBanner("",gd,gt,ti);});
                                    while(nextCategory<cats.size()&&inFlight<categoryWindowSize){
                                        final int categoryIndex=nextCategory++;
                                        final Category category=cats.get(categoryIndex);
                                        categoryResults.submit(()->{
                                            waitWhilePaused();waitForLibraryLoad();
                                            com.nenotv.player.net.StreamingJsonArray.checkCancelled();
                                            ((XtreamProvider)indexProvider).streamCategory(type,category.id,batch->{
                                                waitWhilePaused();waitForLibraryLoad();
                                                for(MediaEntry e:batch)e.group=category.name;
                                                searchIndex.importBatch(importSession,key,type,batch,publishPartial);
                                            });
                                            return categoryIndex;
                                        });
                                        inFlight++;
                                    }
                                }
                            }catch(InterruptedException interrupted){
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(interrupted);
                            }finally{
                                categoryPool.shutdownNow();
                            }
                            searchIndex.finishSectionImport(importSession,key,type);fallbackSession=null;
                            searchIndex.clearImportProgress(key,type);
                            SettingsStore.prefs(this).edit().remove(cacheCursorKey(key,type)).apply();
                            if(key.equals(profileKey())){publishIndexedTop(type,true);reloadIndexedSectionWhenReady(type);}
                        }finally{
                            // Deliberately keep an unfinished staging session and checkpoint after process death/cancellation.
                        }
                    }

                    allComplete=searchIndex.isComplete(key,"vod")&&searchIndex.isComplete(key,"series")&&searchIndex.isComplete(key,"live");
                    if(allComplete){
                        sp.edit().putInt("language_index_version_"+key,schema).putBoolean("language_index_rebuild_started_"+key,false)
                          .putBoolean("first_sync_done_"+key,true).putInt("first_sync_done_count_"+key,grandTotal).putInt("first_sync_total_count_"+key,grandTotal).apply();
                    }
                }
            }catch(Exception failure){
                if(!Thread.currentThread().isInterrupted())runOnUiThread(()->{if(isUiAlive()&&key.equals(profileKey()))status.setText(T("loading_interrupted"));});
            }finally{
                indexCategoryBusy=false;synchronized(MainActivity.this){indexRefreshRunning=false;indexFuture=null;}final boolean done=allComplete;
                runOnUiThread(()->{if(!isUiAlive()||!key.equals(profileKey()))return;if(done)hideIndexBanner("");else restoreFirstSyncBanner();if(indexRefreshRequested){indexRefreshRequested=false;refreshSearchIndex(true);}});
            }
        });
    }

    void waitWhilePaused(){while(activityPaused&&!playbackActive&&!isFinishing()&&!isDestroyed()&&!Thread.currentThread().isInterrupted()){try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}}
    void waitForLibraryLoad(){while(fullLibraryToken!=0&&!isFinishing()&&!isDestroyed()&&!Thread.currentThread().isInterrupted()){try{Thread.sleep(180);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}}
    boolean isUiAlive(){return !isFinishing()&&!isDestroyed();}


    void showLocal(List<MediaEntry>x,String label){nextRequest();section="local";categories.setVisibility(View.GONE);genreButton.setVisibility(View.GONE);sortButton.setVisibility(View.GONE);filterBar.setVisibility(View.GONE);showMediaGrid(false);all=x;gridAdapter.set(x,false);setHeroDefault(label,x.isEmpty()?"":T("tap_preview"));busy(false,x.size()+" "+T("results"));}
    void setHeroDefault(String main,String sub){selectedHero=null;heroSerial++;heroImage.setImageDrawable(null);heroImage.setBackgroundColor(0x00000000);heroAction.setVisibility(View.GONE);heroInfo.setVisibility(View.GONE);heroTitle.setText(main);heroSubtitle.setText(sub);}
    void preview(MediaEntry e){preview(e,true);} void previewAuto(MediaEntry e){preview(e,false);} void preview(MediaEntry e,boolean interest){if(isAdultLocked(e))return;if(interest)library.noteInterest(e);selectedHero=e;heroTitle.setText(DisplayText.title(e));String meta=DisplayText.meta(e);if((meta==null||meta.trim().isEmpty())&&"live".equals(e.type))meta=e.group;if(meta==null||meta.trim().isEmpty())meta="series".equals(e.type)?T("series"):"live".equals(e.type)?T("live_tv"):T("movie");heroSubtitle.setText(meta);final String heroMeta=meta,heroKey=e.uniqueKey();if(heroMeta!=null&&!heroMeta.trim().isEmpty())InfoTranslator.translate(heroMeta,SettingsStore.primaryLanguage(this),translated->runOnUiThread(()->{if(isUiAlive()&&selectedHero!=null&&heroKey.equals(selectedHero.uniqueKey())&&translated!=null&&!translated.trim().isEmpty())heroSubtitle.setText(translated);}));heroAction.setText("series".equals(e.type)?T("episodes"):"▶  "+T("play"));heroAction.setVisibility(View.VISIBLE);heroInfo.setVisibility("live".equals(e.type)?View.GONE:View.VISIBLE);loadHeroImage(e);}
    void loadHeroImage(MediaEntry e){final int ticket=++heroSerial;final String expected=e.uniqueKey();String immediate=e.backdrop==null||e.backdrop.trim().isEmpty()?e.logo:e.backdrop;Bitmap cached=immediate==null?null:HERO_CACHE.get(immediate);if(cached!=null){heroImage.setImageBitmap(cached);return;}heroImage.setImageDrawable(null);heroExec.execute(()->{String url=e.backdrop;try{if(provider instanceof XtreamProvider)url=((XtreamProvider)provider).backdrop(e);}catch(Exception ignored){}if(url==null||url.trim().isEmpty())url=e.logo;final String chosen=url;Bitmap bm=chosen==null||chosen.trim().isEmpty()?null:HERO_CACHE.get(chosen);if(bm==null&&chosen!=null&&!chosen.trim().isEmpty()){bm=downloadHero(chosen);if(bm!=null)HERO_CACHE.put(chosen,bm);}final Bitmap ready=bm;runOnUiThread(()->{if(!isUiAlive()||ticket!=heroSerial||selectedHero==null||!expected.equals(selectedHero.uniqueKey()))return;if(ready!=null)heroImage.setImageBitmap(ready);else heroImage.setImageDrawable(null);});});}
    Bitmap downloadHero(String url){HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(9000);c.setUseCaches(true);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android) SunnyIPTV/0.5.2");c.setRequestProperty("Accept","image/*,*/*;q=0.8");c.setRequestProperty("Connection","close");int code=c.getResponseCode();if(code<200||code>=300)return null;byte[]data=readHero(c.getInputStream());if(data.length==0)return null;BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);int sample=1;while(bounds.outWidth/sample>1600||bounds.outHeight/sample>1000)sample*=2;BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=Math.max(1,sample);o.inPreferredConfig=Bitmap.Config.RGB_565;return BitmapFactory.decodeByteArray(data,0,data.length,o);}catch(Exception ignored){return null;}finally{if(c!=null)c.disconnect();}}
    byte[] readHero(InputStream raw)throws IOException{try(InputStream in=raw;ByteArrayOutputStream out=new ByteArrayOutputStream(256*1024)){byte[]b=new byte[8192];int n,total=0;while((n=in.read(b))>0){total+=n;if(total>8*1024*1024)break;out.write(b,0,n);}return out.toByteArray();}}

    void select(MediaEntry e){if(isAdultLocked(e)){FamilyUi.blocked(this);return;}if(e.type.equals("series")){stopCachePaging();final int token=nextRequest();busy(true,T("episodes_loading"));exec.execute(()->{try{List<MediaEntry>x=EpisodeOrder.sorted(providerFor(e).seriesEpisodes(e));for(MediaEntry ep:x){ep.sourceId=e.sourceId;ep.sourceName=e.sourceName;if(ep.categoryId==null||ep.categoryId.isEmpty())ep.categoryId=e.categoryId;}List<MediaEntry> permitted=visibleItems(x);runOnUiThread(()->{if(current(token)){List<MediaEntry> allowedEpisodes=visibleItems(permitted);seriesEpisodeMode=true;showMediaList();categories.setVisibility(View.GONE);genreButton.setVisibility(View.GONE);all=allowedEpisodes;seriesEpisodes=new ArrayList<>(allowedEpisodes);adapter.set(allowedEpisodes);showSeasons(allowedEpisodes);MediaEntry next=nextEpisodeFor(e,allowedEpisodes);heroTitle.setText(DisplayText.title(e));if(next!=null){selectedHero=next;heroSubtitle.setText((library.progress(next)>0?T("continue"):T("next_episode"))+" · S"+next.season+"E"+next.episode+" · "+DisplayText.title(next));heroAction.setText(library.progress(next)>0?"▶  "+T("continue"):"▶  "+T("next_episode"));heroAction.setVisibility(View.VISIBLE);heroInfo.setVisibility(View.VISIBLE);}else{selectedHero=e;heroSubtitle.setText(T("no_episodes"));heroAction.setVisibility(View.GONE);heroInfo.setVisibility(View.VISIBLE);}busy(false,allowedEpisodes.size()+" "+T("episodes"));}});}catch(Exception ex){runOnUiThread(()->{if(current(token))busy(false,T("series_error")+": "+friendly(ex));});}});return;}play(e);}
    MediaEntry nextEpisodeFor(MediaEntry series,List<MediaEntry> episodes){return EpisodeOrder.next(episodes,library);}
    void wireSeasons(){
        seasonSpinner=findViewById(R.id.seasonSpinner);
        seasonSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(settingSeasons||!seriesEpisodeMode)return;List<Integer> values=(List<Integer>)seasonSpinner.getTag();if(values==null||position>=values.size())return;int season=values.get(position);List<MediaEntry> shown=new ArrayList<>();for(MediaEntry e:seriesEpisodes)if(season<0||e.season==season)shown.add(e);all=shown;adapter.set(shown);busy(false,shown.size()+" "+T("episodes"));}});
    }
    void hideSeasons(){if(seasonSpinner!=null)seasonSpinner.setVisibility(View.GONE);seriesEpisodes.clear();}
    void showSeasons(List<MediaEntry> entries){
        TreeSet<Integer> numbers=new TreeSet<>();for(MediaEntry e:entries)numbers.add(e.season);
        List<Integer> values=new ArrayList<>();values.add(-1);values.addAll(numbers);
        List<String> labels=new ArrayList<>();labels.add(T("all_seasons"));for(int n:numbers)labels.add(T("season")+" "+n);
        settingSeasons=true;seasonSpinner.setTag(values);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);seasonSpinner.setAdapter(a);seasonSpinner.setSelection(0,false);seasonSpinner.setVisibility(entries.isEmpty()?View.GONE:View.VISIBLE);settingSeasons=false;
    }

    void setHeroHeight(int px){ViewGroup.LayoutParams lp=findViewById(R.id.heroContainer).getLayoutParams();if(lp.height!=px){lp.height=px;findViewById(R.id.heroContainer).setLayoutParams(lp);}}

    void showDetails(MediaEntry item){
        if(isAdultLocked(item)){FamilyUi.blocked(this);return;}
        if(item==null||provider==null)return; busy(true,T("info_loading"));
        exec.execute(()->{try{MediaDetails d=providerFor(item).details(item);runOnUiThread(()->{if(!isUiAlive())return;busy(false,"");showDetailsDialog(item,d);});}catch(Exception ex){runOnUiThread(()->busy(false,T("info_failed")+": "+friendly(ex)));}});
    }

    Dialog showDetailsDialog(MediaEntry item,MediaDetails details){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xFF171A20);
        ImageView backdrop=new ImageView(this);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setAlpha(.22f);root.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(18),dp(18),dp(18));scroll.addView(box);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.HORIZONTAL);ImageView poster=new ImageView(this);header.addView(poster,new LinearLayout.LayoutParams(dp(96),dp(144)));MediaRowAdapter.loadArtwork(poster,safe(details.poster).isEmpty()?item.logo:details.poster,item.name,240,360);
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);words.setPadding(dp(14),0,0,0);TextView title=new TextView(this);title.setText(DisplayText.cleanTitle(safe(details.title).isEmpty()?item.name:details.title));title.setTextColor(Color.WHITE);title.setTextSize(22);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(4);words.addView(title);
        List<String> facts=new ArrayList<>();if(!safe(details.year).isEmpty())facts.add(details.year);if(!safe(details.genre).isEmpty())facts.add(localizeGenreText(details.genre));if(!safe(details.duration).isEmpty())facts.add(details.duration);if(!safe(details.country).isEmpty())facts.add(details.country);if(!facts.isEmpty())addInfoLine(words,TextUtils.join(" · ",facts),false);if(!details.scoreLabel().isEmpty())addInfoLine(words,details.scoreLabel(),true);header.addView(words,new LinearLayout.LayoutParams(0,-2,1));box.addView(header);
        final Dialog dialog=new Dialog(this);boolean narrowDetails=getResources().getConfiguration().screenWidthDp<600;LinearLayout actions=new LinearLayout(this);actions.setOrientation(narrowDetails?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);actions.setPadding(0,dp(14),0,0);
        Button play=new Button(this);play.setText("series".equals(item.type)?T("episodes"):library.progress(item)>0?T("continue"):T("play"));play.setAllCaps(false);play.setOnClickListener(v->{dialog.dismiss();select(item);});play.setMinHeight(dp(50));play.setMinWidth(0);actions.addView(play,narrowDetails?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));
        Button favorite=new Button(this);favorite.setAllCaps(false);favorite.setTextSize(13);favorite.setText(library.isFavorite(item)?"★ "+T("remove_favorite"):"☆ "+T("add_favorite"));favorite.setOnClickListener(v->{library.toggleFavorite(item);favorite.setText(library.isFavorite(item)?"★ "+T("remove_favorite"):"☆ "+T("add_favorite"));adapter.notifyDataSetChanged();gridAdapter.notifyDataSetChanged();});favorite.setTag("details-favorite");favorite.setMinHeight(dp(50));favorite.setMinWidth(0);actions.addView(favorite,narrowDetails?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));box.addView(actions);
        if(!safe(details.director).isEmpty())addInfoBlock(box,T("director"),details.director);
        if(!safe(details.plot).isEmpty()){TextView plot=addInfoBlock(box,T("description"),details.plot);android.text.util.Linkify.addLinks(plot,android.text.util.Linkify.WEB_URLS);if(!DemoSource.isEntry(item))InfoTranslator.translate(details.plot,SettingsStore.primaryLanguage(this),out->runOnUiThread(()->{if(isUiAlive()&&dialog.isShowing()){plot.setText(out);android.text.util.Linkify.addLinks(plot,android.text.util.Linkify.WEB_URLS);}}));}
        if(!safe(details.cast).isEmpty()){Button cast=new Button(this);cast.setText(T("show_cast"));cast.setAllCaps(false);box.addView(cast);TextView names=addInfoBlock(box,T("cast"),details.cast);names.setVisibility(View.GONE);cast.setOnClickListener(v->names.setVisibility(names.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));}
        LinearLayout links=new LinearLayout(this);links.setGravity(Gravity.END);if(details.hasImdb()){Button b=new Button(this);b.setText("IMDb");b.setOnClickListener(v->openWeb("https://www.imdb.com/title/"+details.imdbId+"/"));links.addView(b);}if(details.hasTmdb()){Button b=new Button(this);b.setText("TMDb");b.setOnClickListener(v->openWeb("https://www.themoviedb.org/"+(("series".equals(item.type)||"episode".equals(item.type))?"tv/":"movie/")+details.tmdbId));links.addView(b);}Button close=new Button(this);close.setText(T("close"));close.setAllCaps(false);close.setOnClickListener(v->dialog.dismiss());links.addView(close);box.addView(links);
        dialog.setContentView(root);dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*.93f),(int)(getResources().getDisplayMetrics().heightPixels*.82f));
        final String url=!safe(details.backdrop).isEmpty()?details.backdrop:item.backdrop;if(url!=null&&!url.isEmpty())heroExec.execute(()->{Bitmap bitmap=downloadHero(url);runOnUiThread(()->{if(isUiAlive()&&dialog.isShowing()&&bitmap!=null)backdrop.setImageBitmap(bitmap);});});
        return dialog;
    }

void addInfoLine(LinearLayout b,String x,boolean strong){TextView v=new TextView(this);v.setText(x);v.setTextColor(0xFFF7F8FA);v.setTextSize(strong?16:14);if(strong)v.setTypeface(null,Typeface.BOLD);v.setPadding(0,dp(8),0,0);b.addView(v);}
TextView addInfoBlock(LinearLayout b,String l,String x){TextView h=new TextView(this);h.setText(l);h.setTextColor(0xFFF7F8FA);h.setTextSize(15);h.setTypeface(null,Typeface.BOLD);h.setPadding(0,dp(14),0,dp(3));b.addView(h);TextView v=new TextView(this);v.setText(x);v.setTextColor(0xFFE5E8ED);v.setTextSize(14);b.addView(v);return v;}
    String localizeGenreText(String raw){if(raw==null||raw.trim().isEmpty())return raw;String x=raw;String[][]g={{"science fiction",T("genre_scifi")},{"sci-fi",T("genre_scifi")},{"documentary",T("genre_documentary")},{"animation",T("genre_animation")},{"comedy",T("genre_comedy")},{"horror",T("genre_horror")},{"romance",T("genre_romance")},{"fantasy",T("genre_fantasy")},{"family",T("genre_family")},{"crime",T("genre_crime")},{"thriller",T("genre_thriller")},{"drama",T("genre_drama")},{"action",T("genre_action").replace(" / ","/").split("/")[0].trim()}};for(String[]a:g)x=x.replaceAll("(?i)\\b"+java.util.regex.Pattern.quote(a[0])+"\\b",java.util.regex.Matcher.quoteReplacement(a[1]));return x;}
    void openWeb(String url){if(FamilyStore.active(this)){FamilyUi.blocked(this);return;}try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(Exception e){status.setText(T("info_failed"));}}

    void play(MediaEntry e){
        if(isAdultLocked(e)){FamilyUi.blocked(this);return;}
        Profile playbackProfile;
        try{playbackProfile=com.nenotv.player.provider.PlaybackSourceRoute.resolve(this,e,ProLibraryBridge.isActive(this)).profile();}
        catch(Exception unavailable){status.setText(T("source_unavailable"));return;}
        pauseIndexForPlayback();
        if(DemoPolicy.blockPlayback(this)){recreate();return;}library.recent(e);Intent i=ProModuleInstaller.playerIntent(this);i.putExtra("media",e);i.putExtra("profileType",playbackProfile.type.name());
        String queueToken="";
        if("live".equals(e.type)){ArrayList<MediaEntry>q=new ArrayList<>();for(MediaEntry z:all)if("live".equals(z.type)&&!isAdultLocked(z)){q.add(z);if(q.size()>=250)break;}int at=-1;for(int n=0;n<q.size();n++)if(q.get(n).uniqueKey().equals(e.uniqueKey())){at=n;break;}if(at>=0)queueToken=com.nenotv.player.storage.PlaybackQueueStore.put("live",q,at);}
        else if("episode".equals(e.type)&&seriesEpisodeMode){ArrayList<MediaEntry>q=new ArrayList<>();for(MediaEntry z:seriesEpisodes)if("episode".equals(z.type)&&!isAdultLocked(z))q.add(z);int at=-1;for(int n=0;n<q.size();n++)if(q.get(n).uniqueKey().equals(e.uniqueKey())){at=n;break;}if(at>=0)queueToken=com.nenotv.player.storage.PlaybackQueueStore.put("episode",q,at);}
        if(!queueToken.isEmpty())i.putExtra("queueToken",queueToken);
        try{startActivity(i);}catch(RuntimeException ex){playbackActive=false;activityPaused=false;resumeIndexAfterPlayback=true;resumeIndexSoon();throw ex;}
    }
    void pauseIndexForPlayback(){
        playbackActive=true;activityPaused=true;resumeIndexAfterPlayback=false;
        try{if(provider instanceof XtreamProvider)((XtreamProvider)provider).clearTransientItemCache();}catch(Exception ignored){}
        HERO_CACHE.evictAll();MediaRowAdapter.clearArtworkCache();if(heroImage!=null)heroImage.setImageDrawable(null);
    }
    void resumeIndexSoon(){
        if(!resumeIndexAfterPlayback||provider==null)return;
        ui.postDelayed(new Runnable(){@Override public void run(){if(!isUiAlive()||provider==null)return;if(indexRefreshRunning){ui.postDelayed(this,250);return;}resumeIndexAfterPlayback=false;refreshSearchIndex(false);}},300);
    }
    void actions(MediaEntry e){if(isAdultLocked(e)){FamilyUi.blocked(this);return;}List<String>opts=new ArrayList<>();opts.add(library.isFavorite(e)?T("remove_favorite"):T("add_favorite"));if("live".equals(e.type))opts.add(T("full_guide"));else if(library.progress(e)>0)opts.add(T("clear_progress"));final int familyOption=opts.size();if(!"series".equals(e.type))opts.add(FamilyUi.text(this,FamilyStore.approved(this,e)?"Uit kindermodus verwijderen":"Toestaan in kindermodus",FamilyStore.approved(this,e)?"Remove from child mode":"Allow in child mode",FamilyStore.approved(this,e)?"Aus Kindermodus entfernen":"Im Kindermodus erlauben"));new AlertDialog.Builder(this).setTitle(DisplayText.title(e)).setItems(opts.toArray(new String[0]),(d,w)->{if(w==familyOption){FamilyUi.pin(this,pin->{boolean ok=FamilyStore.approved(this,e)?FamilyStore.revoke(this,FamilyStore.approvalKey(this,e),pin):FamilyStore.approve(this,e,pin);if(ok)recreate();else FamilyUi.blocked(this);});return;}if(w==0){library.toggleFavorite(e);adapter.notifyDataSetChanged();gridAdapter.notifyDataSetChanged();if("home".equals(section))loadHome();}else if("live".equals(e.type)){status.setText(T("loading"));exec.execute(()->{try{String g=epgRequests().text(e);if(g.isEmpty())g=T("no_epg");InfoTranslator.translate(g,SettingsStore.primaryLanguage(this),translated->runOnUiThread(()->{if(isUiAlive())new AlertDialog.Builder(this).setTitle(DisplayText.title(e)).setMessage(translated).setPositiveButton(T("watch"),(dd,ww)->play(e)).setNegativeButton(T("close"),null).show();}));}catch(Exception ex){runOnUiThread(()->status.setText(T("epg_error")+": "+friendly(ex)));}});}else{library.clearProgress(e);Toast.makeText(this,T("progress_cleared"),Toast.LENGTH_SHORT).show();if("home".equals(section))loadHome();}}).show();}

    List<Category> visibleCategories(List<Category>src){if(FamilyStore.active(this))return new ArrayList<>();if(SettingsStore.adultsAllowed(this))return new ArrayList<>(src);List<Category>o=new ArrayList<>();for(Category c:src)if(!SettingsStore.isAdultLabel(c.name))o.add(c);return o;}
    boolean isAdultLocked(MediaEntry e){return e==null||!FamilyStore.allowed(this,e)||(!SettingsStore.adultsAllowed(this)&&(SettingsStore.isAdultLabel(e.group)||SettingsStore.isAdultLabel(e.name)));}
    List<MediaEntry> visibleItems(List<MediaEntry>src){List<MediaEntry>o=new ArrayList<>();if(src==null)return o;for(MediaEntry e:src)if(!isAdultLocked(e))o.add(e);return o;}
    int hiddenCount(List<MediaEntry>a,List<MediaEntry>b){return Math.max(0,(a==null?0:a.size())-(b==null?0:b.size()));}
    void showStreamingBanner(String type,int titles){
        if(indexBanner==null||indexBannerText==null)return;
        indexBanner.setVisibility(View.VISIBLE);
        indexBannerText.setText(label(type)+" · "+titles+" "+T("loaded"));
        if(indexBannerProgress!=null)indexBannerProgress.setIndeterminate(true);
    }
    void showIndexBanner(String type,int done,int cats,int titles){
    if(indexBanner==null||indexBannerText==null)return;
    android.content.SharedPreferences sp=SettingsStore.prefs(this);String key=profileKey();
    if(sp.getBoolean("first_sync_done_"+key,false)){indexBanner.setVisibility(View.GONE);return;}
    int total=Math.max(1,cats),finished=Math.max(0,Math.min(done,total));
    int pct=Math.max(0,Math.min(100,(finished*100)/total));
    sp.edit().putInt("first_sync_done_count_"+key,finished).putInt("first_sync_total_count_"+key,total).putInt("first_sync_titles_"+key,Math.max(0,titles)).apply();
    indexBannerText.setText(T("loading")+" · "+pct+"%");
    if(indexBannerProgress!=null){indexBannerProgress.setIndeterminate(false);indexBannerProgress.setMax(100);indexBannerProgress.setProgress(pct);}
    indexBanner.setVisibility(View.VISIBLE);
}

    void restoreFirstSyncBanner(){
        if(indexBanner==null||profile==null)return;
        android.content.SharedPreferences sp=SettingsStore.prefs(this);String key=profileKey();
        if(sp.getBoolean("first_sync_done_"+key,false)){indexBanner.setVisibility(View.GONE);return;}
        int total=sp.getInt("first_sync_total_count_"+key,0);
        int done=sp.getInt("first_sync_done_count_"+key,0);
        int titles=Math.max(sp.getInt("first_sync_titles_"+key,0),searchIndex.count(key));
        if(total>0)showIndexBanner("",done,total,titles);
    }

    void hideIndexBanner(String type){if(indexBanner==null)return;Object t=indexBanner.getTag();if(type==null||type.isEmpty()||t==null||type.equals(String.valueOf(t))){indexBanner.setVisibility(View.GONE);indexBanner.setTag(null);}}
    List<MediaEntry> sortItems(List<MediaEntry>src){
        List<MediaEntry>o=new ArrayList<>(src==null?Collections.emptyList():src);
        String mode=SettingsStore.sort(this);
        Comparator<MediaEntry>az=(a,b)->safe(a.name).compareToIgnoreCase(safe(b.name));
        if("az".equals(mode))o.sort(az);
        else if("za".equals(mode))o.sort(az.reversed());
        else if("favorites".equals(mode))o.sort((a,b)->{int x=Boolean.compare(library.isFavorite(b),library.isFavorite(a));return x!=0?x:az.compare(a,b);});
        else if("recent".equals(mode))o.sort((a,b)->{int x=Integer.compare(library.recentRank(a),library.recentRank(b));return x!=0?x:az.compare(a,b);});
        if("provider".equals(mode))o=ProLibraryBridge.optimize(this,o,section,SettingsStore.contentLanguage(this));
        return o;
    }
    void showSortChooser(){String[]labels={ProLibraryBridge.isActive(this)?"Pro · "+T("recommended"):T("provider"),"A–Z","Z–A",T("favorites_first"),T("recent_first")};String[]values={"provider","az","za","favorites","recent"};String cur=SettingsStore.sort(this);int checked=0;for(int i=0;i<values.length;i++)if(values[i].equals(cur))checked=i;new AlertDialog.Builder(this).setTitle(T("sort")).setSingleChoiceItems(labels,checked,(d,w)->{SettingsStore.prefs(this).edit().putString("sort",values[w]).apply();d.dismiss();if(section.equals("live")||section.equals("vod")||section.equals("series")){
        if(currentCategoryId.startsWith("lang:")){loadLanguageGroup(currentCategoryId.substring(5),false);return;}
        if("multi".equals(currentCategoryId)){loadLanguageGroup("multi",false);return;}
        if("other".equals(currentCategoryId)){loadOtherGroup(false);return;}
        if("all".equals(currentCategoryId)){loadItems("all");return;}
    }if(fullLibraryToken!=0&&(section.equals("live")||section.equals("vod")||section.equals("series"))){status.setText(T("sort")+" ‘"+labels[w]+"’ "+T("sorting_applied_after"));return;}if("epg".equals(section)){List<MediaEntry>x=sortItems(all);all=x;showEpgByMode(x);busy(false,x.size()+" "+T("channels")+" · "+labels[w]);}else if("live".equals(section)||"vod".equals(section)||"series".equals(section)){final String sec=section;final int token=nextRequest();final ArrayList<MediaEntry>snap=new ArrayList<>(all);busy(true,T("sorting"));exec.execute(()->{List<MediaEntry>x=sortItems(snap);runOnUiThread(()->{if(!current(token)||!section.equals(sec))return;all=x;showMediaGrid("live".equals(section));gridAdapter.set(x,"live".equals(section));busy(false,x.size()+" "+T("results")+" · "+labels[w]);});});}else loadHome();}).show();}

    String label(String s){return s.equals("live")?T("live_tv"):s.equals("vod")?T("movies"):s.equals("series")?T("series"):s.equals("epg")?T("epg"):"SunnyIPTV";} String friendly(Exception e){String m=e.getMessage();if(m==null||m.trim().isEmpty())return T("unknown_error");if("SOURCE_UNAVAILABLE".equals(m))return T("source_unavailable");if("SOURCE_CHANGED".equals(m))return T("source_changed");return m.replace("LOGIN_FAILED",T("login_failed"));} String friendlyThrowable(Throwable e){if(e instanceof OutOfMemoryError)return T("low_memory");String m=e==null?null:e.getMessage();return m==null||m.trim().isEmpty()?e.getClass().getSimpleName():m;} void busy(boolean b,String s){progress.setVisibility(b?View.VISIBLE:View.GONE);status.setText(s);}
    static Intent firstRunIntent(android.content.Context context){return new Intent(context,PairingActivity.class).putExtra("first_run",true).putExtra("mandatory_login",true);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(updateNotifier!=null)updateNotifier.onActivityResult(r,c);if(r==10&&profiles.exists())openProfile();}
    @Override protected void onResume(){super.onResume();if(isFinishing())return;if(!new AccountLinkStore(this).recent()){startActivity(new Intent(this,AccountCheckActivity.class));finish();return;}long familyNow=FamilyStore.revision(this);if(familyRevision>=0&&familyRevision!=familyNow){familyRevision=familyNow;recreate();return;}familyRevision=familyNow;if(!isFinishing()){try{if(updateNotifier==null)updateNotifier=new PlayUpdateNotifier(this,()->{ProModuleInstaller.syncEntitlement(this);updateHeaderBadges();});updateNotifier.onResume();}catch(Exception ignored){}}if(provider!=null&&expireDemoProfileIfNeeded()){recreate();return;}ProModuleInstaller.syncEntitlement(this);playbackActive=false;activityPaused=false;updateHeaderBadges();if(!profileReady)return;restoreFirstSyncBanner();resumeIndexSoon();String nowLang=SettingsStore.language(this);if(appliedLanguage!=null&&!appliedLanguage.isEmpty()&&!appliedLanguage.equals(nowLang)){recreate();return;}String nowContent=SettingsStore.contentLanguage(this);if(appliedContentLanguage!=null&&!appliedContentLanguage.isEmpty()&&!appliedContentLanguage.equals(nowContent)){appliedContentLanguage=nowContent;if(provider!=null){if("home".equals(section)||"local".equals(section))loadHome();else if("epg".equals(section))loadEpg();else loadSection(section);}return;}if(provider!=null){EpgRequests requests=epgRequests();adapter.setEpg(requests);gridAdapter.setEpg(requests);epgAdapter.configure(requests);gridAdapter.notifyDataSetChanged();epgAdapter.notifyDataSetChanged();if(seriesEpisodeMode){MediaEntry next=EpisodeOrder.next(seriesEpisodes,library);if(next!=null){selectedHero=next;heroSubtitle.setText((library.progress(next)>0?T("continue"):T("next_episode"))+" · S"+next.season+"E"+next.episode);heroAction.setText(library.progress(next)>0?T("continue"):T("next_episode"));heroAction.setVisibility(View.VISIBLE);}else heroAction.setVisibility(View.GONE);adapter.notifyDataSetChanged();}else if("home".equals(section)||"local".equals(section))loadHome();setHeroHeight(heroHeight());android.content.SharedPreferences sp=SettingsStore.prefs(this);if(sp.getBoolean("force_reindex",false)){sp.edit().putBoolean("force_reindex",false).apply();refreshSearchIndex(true);}}}
    @Override protected void onPause(){if(updateNotifier!=null)updateNotifier.onPause();activityPaused=true;super.onPause();}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW){HERO_CACHE.evictAll();MediaRowAdapter.clearArtworkCache();}}
    @Override public void onBackPressed(){if(seriesEpisodeMode){loadSection("series");}else super.onBackPressed();}
    @Override protected void onDestroy(){if(updateNotifier!=null)updateNotifier.close();requestSerial++;heroSerial++;if(pendingSearch!=null)ui.removeCallbacks(pendingSearch);if(delayedIndexResume!=null)ui.removeCallbacks(delayedIndexResume);Future<?> f=indexFuture;if(f!=null)f.cancel(true);exec.shutdownNow();heroExec.shutdownNow();indexExec.shutdownNow();closeSearchIndexAfterWorkers();epgExec.shutdownNow();if(adapter!=null)adapter.setEpg(null);if(gridAdapter!=null)gridAdapter.setEpg(null);if(epgAdapter!=null)epgAdapter.shutdown();if(epgStore!=null)epgStore.close();super.onDestroy();}
    void closeSearchIndexAfterWorkers(){
        new Thread(()->{
            try{
                while(!exec.awaitTermination(30,TimeUnit.SECONDS)){}
                while(!indexExec.awaitTermination(30,TimeUnit.SECONDS)){}
                if(searchIndex!=null)searchIndex.close();
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        },"nenotv-cache-close").start();
    }
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}


