(function(){
"use strict";
function $(id){return document.getElementById(id);}
var state={section:"home",profile:null,settings:{language:"nl",liveFormat:"m3u8",autoReconnect:true},live:[],movies:[],series:[],favorites:[],recent:[],selected:null,playing:null,retry:0,hideUiTimer:null};

function loadLocal(){
  try{state.profile=JSON.parse(localStorage.getItem("nivaro.profile")||"null");}catch(e){}
  try{state.settings=Object.assign(state.settings,JSON.parse(localStorage.getItem("nivaro.settings")||"{}"));}catch(e){}
  try{state.favorites=JSON.parse(localStorage.getItem("nivaro.favorites")||"[]");}catch(e){}
  try{state.recent=JSON.parse(localStorage.getItem("nivaro.recent")||"[]");}catch(e){}
  $("languageSelect").value=state.settings.language;
  $("liveFormat").value=state.settings.liveFormat;
  $("autoReconnect").checked=!!state.settings.autoReconnect;
}
function saveLocal(k,v){localStorage.setItem(k,JSON.stringify(v));}
function toast(x){var t=$("toast");t.textContent=x;t.classList.remove("hidden");setTimeout(function(){t.classList.add("hidden");},2200);}
function safe(s){return s==null?"":String(s);}
function cleanTitle(x){return safe(x).replace(/^\s*\|[^|]{1,12}\|\s*/,"").trim();}
function art(x){return x&&safe(x.stream_icon||x.cover||x.logo||x.poster_path||x.backdrop_path);}
function mediaKey(x){return [x&&x._type,x&&(x.stream_id||x.series_id||x.id),x&&x.name].join(":");}
function normalize(items,type){return (items||[]).map(function(x){return Object.assign({},x,{_type:type,_title:cleanTitle(x.name||x.title||"Onbekend")});});}
function openOverlay(id){$(id).classList.remove("hidden");setTimeout(function(){var f=$(id).querySelector(".focusable");if(f)f.focus();},50);}
function closeOverlay(el){var o=el.closest(".overlay");if(o)o.classList.add("hidden");}

function serviceCall(method,payload){
  return new Promise(function(resolve,reject){
    if(window.webOS&&webOS.service&&webOS.service.request){
      var request=webOS.service.request("luna://com.nivaroplayer.app.service",{
        method:method,
        parameters:payload,
        onSuccess:function(r){resolve(r);},
        onFailure:function(e){reject(e);}
      });
      window.__nivaroRequest=request;
    }else reject(new Error("webOSTV.js niet beschikbaar"));
  });
}
function endpoint(action,extra){return Object.assign({base:state.profile.server,username:state.profile.username,password:state.profile.password,action:action},extra||{});}
async function loadXtream(){
  $("loading").textContent="Provider laden…";
  var all=await Promise.all([
    serviceCall("xtream",endpoint("get_live_streams")),
    serviceCall("xtream",endpoint("get_vod_streams")),
    serviceCall("xtream",endpoint("get_series"))
  ]);
  state.live=normalize(all[0].data,"live");
  state.movies=normalize(all[1].data,"movie");
  state.series=normalize(all[2].data,"series");
  render();
}
async function connectProfile(){
  var type=$("sourceType").value;
  if(type==="xtream"){
    var p={type:type,server:$("serverUrl").value.trim().replace(/\/+$/,""),username:$("username").value.trim(),password:$("password").value};
    if(!p.server||!p.username||!p.password){$("setupStatus").textContent="Vul server, gebruikersnaam en wachtwoord in.";return;}
    $("setupStatus").textContent="Verbinden…";
    try{
      var auth=await serviceCall("xtream",{action:"auth",base:p.server,username:p.username,password:p.password});
      if(!auth||auth.ok===false)throw new Error(auth&&auth.error||"Verbinding mislukt");
      state.profile=p;saveLocal("nivaro.profile",p);$("setupOverlay").classList.add("hidden");await loadXtream();
    }catch(e){$("setupStatus").textContent="Kan niet verbinden: "+safe(e.message||e.errorText||e);}
  }else{
    var m={type:type,m3u:$("m3uUrl").value.trim()};
    if(!m.m3u){$("setupStatus").textContent="Vul de M3U URL in.";return;}
    try{
      var rr=await serviceCall("m3u",{url:m.m3u});
      state.profile=m;saveLocal("nivaro.profile",m);state.live=normalize(rr.data,"live");state.movies=[];state.series=[];$("setupOverlay").classList.add("hidden");render();
    }catch(e2){$("setupStatus").textContent="Kan M3U niet laden: "+safe(e2.message||e2);}
  }
}
function setHero(x){
  state.selected=x||null;
  var b=$("heroBackdrop"),t=$("heroTitle"),s=$("heroSubtitle"),e=$("heroEyebrow"),btn=$("heroButtons");
  if(!x){b.style.backgroundImage="";e.textContent="Nivaro IPTV Player";t.textContent="Kijken zonder zoeken";s.textContent="Live tv, films en series op één plek.";btn.classList.add("hidden");return;}
  var a=art(x);b.style.backgroundImage=a?'url("'+a.replace(/"/g,"%22")+'")':"";
  e.textContent=x._type==="live"?"Live tv":x._type==="series"?"Serie":"Film";
  t.textContent=x._title;s.textContent=safe(x.plot||x.genre||x.category_name||"");btn.classList.remove("hidden");
}
function card(x){
  var d=document.createElement("div");d.className="card focusable";d.tabIndex=0;d.dataset.key=mediaKey(x);
  var p=document.createElement("div");p.className="poster";var a=art(x);if(a)p.style.backgroundImage='url("'+a.replace(/"/g,"%22")+'")';d.appendChild(p);
  var n=document.createElement("div");n.className="card-title";n.textContent=x._title;d.appendChild(n);
  var sub=document.createElement("div");sub.className="card-sub";sub.textContent=safe(x.releaseDate||x.year||x.genre||"");d.appendChild(sub);
  d.addEventListener("focus",function(){setHero(x);});d.addEventListener("click",function(){showInfo(x);});return d;
}
function shelf(title,items){
  if(!items||!items.length)return null;
  var sec=document.createElement("section");sec.className="shelf";
  var h=document.createElement("h2");h.className="shelf-title";h.textContent=title;sec.appendChild(h);
  var row=document.createElement("div");row.className="row";items.slice(0,18).forEach(function(x){row.appendChild(card(x));});sec.appendChild(row);return sec;
}
function channelRow(x){
  var r=document.createElement("div");r.className="channel-row focusable";r.tabIndex=0;r.dataset.key=mediaKey(x);
  var logo=document.createElement("div");logo.className="channel-logo";if(art(x))logo.style.backgroundImage='url("'+art(x)+'")';r.appendChild(logo);
  var n=document.createElement("div");n.className="channel-name";n.textContent=x._title;r.appendChild(n);
  var pr=document.createElement("div");pr.className="program";pr.textContent=safe(x.epg_channel_id||"");r.appendChild(pr);
  r.addEventListener("focus",function(){setHero(x);});r.addEventListener("click",function(){play(x);});return r;
}
function render(){
  document.querySelectorAll(".nav-item").forEach(function(n){n.classList.toggle("active",n.dataset.section===state.section);});
  var c=$("content");c.innerHTML="";
  if(state.section==="home"){
    [["Verder kijken",state.recent],["Mijn favorieten",state.favorites],["Films",state.movies.slice(0,18)],["Series",state.series.slice(0,18)]].forEach(function(pair){var sh=shelf(pair[0],pair[1]);if(sh)c.appendChild(sh);});
  }else if(state.section==="live"){
    state.live.slice(0,180).forEach(function(x){c.appendChild(channelRow(x));});
  }else if(state.section==="movies"){
    var sm=shelf("Films",state.movies);if(sm)c.appendChild(sm);
  }else if(state.section==="series"){
    var ss=shelf("Series",state.series);if(ss)c.appendChild(ss);
  }else if(state.section==="epg"){
    c.innerHTML='<div class="empty">EPG wordt per kanaal geladen. Selecteer een kanaal bij Live voor programma-informatie.</div>';
  }
  if(!c.children.length)c.innerHTML='<div class="empty">Nog geen inhoud beschikbaar.</div>';
  var first=c.querySelector(".focusable");if(first)setTimeout(function(){first.focus();},20);
}
function streamUrl(x){
  if(x.url)return x.url;if(!state.profile||state.profile.type!=="xtream")return "";
  var base=state.profile.server.replace(/\/+$/,""),u=encodeURIComponent(state.profile.username),p=encodeURIComponent(state.profile.password);
  if(x._type==="live")return base+"/live/"+u+"/"+p+"/"+x.stream_id+"."+(state.settings.liveFormat||"m3u8");
  if(x._type==="movie")return base+"/movie/"+u+"/"+p+"/"+x.stream_id+"."+(x.container_extension||"mp4");
  return "";
}
function addRecent(x){state.recent=[x].concat(state.recent.filter(function(y){return mediaKey(y)!==mediaKey(x);})).slice(0,24);saveLocal("nivaro.recent",state.recent);}
function play(x){
  var url=streamUrl(x);if(!url){toast("Deze titel kan nog niet direct worden afgespeeld.");return;}
  state.playing=x;state.retry=0;addRecent(x);
  var v=$("video");$("playerTitle").textContent=x._title;$("playerStatus").textContent="Laden…";$("player").classList.remove("hidden");v.src=url;v.play().catch(function(){});showPlayerUi();
}
function showPlayerUi(){var ui=$("playerUi");ui.classList.remove("hidden");clearTimeout(state.hideUiTimer);state.hideUiTimer=setTimeout(function(){ui.classList.add("hidden");},4500);}
function stopPlayer(){var v=$("video");v.pause();v.removeAttribute("src");v.load();$("player").classList.add("hidden");state.playing=null;render();}
function showInfo(x){
  state.selected=x;$("infoTitle").textContent=x._title;var a=art(x);$("infoBackdrop").style.backgroundImage=a?'url("'+a.replace(/"/g,"%22")+'")':"";
  var meta=[];if(x.year)meta.push("Jaar "+x.year);if(x.genre)meta.push(x.genre);if(x.rating)meta.push("Score "+x.rating);$("infoMeta").textContent=meta.join(" · ");
  $("infoText").textContent=safe(x.plot||x.description||"Geen beschrijving beschikbaar.");
  $("infoFavorite").textContent=state.favorites.some(function(y){return mediaKey(y)===mediaKey(x);})?"★ Favoriet":"☆ Favoriet";openOverlay("infoOverlay");
}
function toggleFavorite(){
  var x=state.selected;if(!x)return;var i=state.favorites.findIndex(function(y){return mediaKey(y)===mediaKey(x);});
  if(i>=0)state.favorites.splice(i,1);else state.favorites.unshift(x);saveLocal("nivaro.favorites",state.favorites);
  $("infoFavorite").textContent=i>=0?"☆ Favoriet":"★ Favoriet";toast(i>=0?"Uit favorieten verwijderd":"Aan favorieten toegevoegd");
}
function openSearch(){openOverlay("searchOverlay");$("searchInput").value="";$("searchResults").innerHTML="";}
function doSearch(q){
  q=q.trim().toLowerCase();var box=$("searchResults");box.innerHTML="";if(q.length<2)return;
  state.live.concat(state.movies,state.series).filter(function(x){return x._title.toLowerCase().indexOf(q)>=0;}).slice(0,32).forEach(function(x){
    var d=document.createElement("div");d.className="search-result focusable";d.tabIndex=0;d.innerHTML="<strong></strong><small></small>";d.querySelector("strong").textContent=x._title;d.querySelector("small").textContent=x._type;
    d.addEventListener("click",function(){$("searchOverlay").classList.add("hidden");showInfo(x);});box.appendChild(d);
  });
}
function section(s){state.section=s;setHero(null);render();}
function setupNavigation(){
  document.addEventListener("keydown",function(e){
    if(!$("player").classList.contains("hidden")){
      var v=$("video");showPlayerUi();
      if(e.key==="Escape"||e.keyCode===461){e.preventDefault();stopPlayer();return;}
      if(e.key==="Enter"||e.keyCode===13){v.paused?v.play():v.pause();return;}
      if(e.key==="ArrowLeft"&&!isNaN(v.duration)){v.currentTime=Math.max(0,v.currentTime-30);return;}
      if(e.key==="ArrowRight"&&!isNaN(v.duration)){v.currentTime=Math.min(v.duration,v.currentTime+30);return;}
      return;
    }
    if(e.key==="Escape"||e.keyCode===461){var o=document.querySelector(".overlay:not(.hidden)");if(o){o.classList.add("hidden");e.preventDefault();return;}}
    if(["ArrowLeft","ArrowRight","ArrowUp","ArrowDown"].indexOf(e.key)<0)return;
    var cur=document.activeElement;if(!cur||!cur.classList.contains("focusable"))return;
    var nodes=[].slice.call(document.querySelectorAll(".focusable")).filter(function(x){return x.offsetParent!==null;});
    var cr=cur.getBoundingClientRect(),best=null,score=1e9;
    nodes.forEach(function(n){
      if(n===cur)return;var r=n.getBoundingClientRect(),dx=(r.left+r.width/2)-(cr.left+cr.width/2),dy=(r.top+r.height/2)-(cr.top+cr.height/2);
      if(e.key==="ArrowRight"&&dx<=8)return;if(e.key==="ArrowLeft"&&dx>=-8)return;if(e.key==="ArrowDown"&&dy<=8)return;if(e.key==="ArrowUp"&&dy>=-8)return;
      var primary=(e.key==="ArrowLeft"||e.key==="ArrowRight")?Math.abs(dx):Math.abs(dy),secondary=(e.key==="ArrowLeft"||e.key==="ArrowRight")?Math.abs(dy):Math.abs(dx),sc=primary+secondary*2.4;
      if(sc<score){score=sc;best=n;}
    });
    if(best){e.preventDefault();best.focus();best.scrollIntoView({block:"nearest",inline:"nearest"});}
  });
}
function bind(){
  document.querySelectorAll(".nav-item").forEach(function(x){x.onclick=function(){section(x.dataset.section);};});
  $("searchBtn").onclick=openSearch;$("settingsBtn").onclick=function(){openOverlay("settingsOverlay");};$("profileBtn").onclick=function(){openOverlay("setupOverlay");};
  document.querySelectorAll(".close-overlay").forEach(function(x){x.onclick=function(){closeOverlay(x);};});
  $("saveProfile").onclick=connectProfile;$("infoPlay").onclick=function(){$("infoOverlay").classList.add("hidden");play(state.selected);};$("infoFavorite").onclick=toggleFavorite;
  $("heroPlay").onclick=function(){play(state.selected);};$("heroInfo").onclick=function(){showInfo(state.selected);};
  $("searchInput").addEventListener("input",function(e){doSearch(e.target.value);});
  $("sourceType").onchange=function(){$("xtreamFields").classList.toggle("hidden",$("sourceType").value!=="xtream");$("m3uFields").classList.toggle("hidden",$("sourceType").value!=="m3u");};
  $("saveSettings").onclick=function(){state.settings.language=$("languageSelect").value;state.settings.liveFormat=$("liveFormat").value;state.settings.autoReconnect=$("autoReconnect").checked;saveLocal("nivaro.settings",state.settings);$("settingsOverlay").classList.add("hidden");toast("Instellingen opgeslagen");};
  $("resetProfile").onclick=function(){localStorage.removeItem("nivaro.profile");state.profile=null;$("settingsOverlay").classList.add("hidden");openOverlay("setupOverlay");};
  var v=$("video");v.addEventListener("playing",function(){$("playerStatus").textContent="";});v.addEventListener("waiting",function(){$("playerStatus").textContent="Bufferen…";});
  v.addEventListener("error",function(){if(state.settings.autoReconnect&&state.playing&&state.retry<2){state.retry++;$("playerStatus").textContent="Opnieuw verbinden…";setTimeout(function(){v.src=streamUrl(state.playing);v.play().catch(function(){});},1800);}else $("playerStatus").textContent="Stream kon niet worden afgespeeld.";});
}
async function boot(){
  loadLocal();bind();setupNavigation();setHero(null);
  if(!state.profile){openOverlay("setupOverlay");return;}
  try{
    if(state.profile.type==="xtream")await loadXtream();
    else{var r=await serviceCall("m3u",{url:state.profile.m3u});state.live=normalize(r.data,"live");render();}
  }catch(e){toast("Provider kon niet worden geladen");openOverlay("setupOverlay");}
}
document.addEventListener("DOMContentLoaded",boot);
})();