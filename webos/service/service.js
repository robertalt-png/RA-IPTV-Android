const Service=require("webos-service");
const http=require("http");
const https=require("https");
const URL=require("url").URL;
const service=new Service("com.nivaroplayer.app.service");

function getText(url,headers){
  return new Promise((resolve,reject)=>{
    const u=new URL(url);
    const lib=u.protocol==="https:"?https:http;
    const req=lib.get(u,{headers:Object.assign({"User-Agent":"Nivaro-webOS/0.1","Accept":"*/*"},headers||{})},res=>{
      if(res.statusCode>=300&&res.statusCode<400&&res.headers.location){
        res.resume();
        getText(new URL(res.headers.location,u).toString(),headers).then(resolve,reject);
        return;
      }
      const chunks=[];
      res.on("data",d=>chunks.push(d));
      res.on("end",()=>{
        const body=Buffer.concat(chunks).toString("utf8");
        if(res.statusCode<200||res.statusCode>=300){reject(new Error("HTTP "+res.statusCode));return;}
        resolve(body);
      });
    });
    req.setTimeout(12000,()=>req.destroy(new Error("Timeout")));
    req.on("error",reject);
  });
}

function cleanBase(x){return String(x||"").replace(/\/+$/,"");}

service.register("xtream",async message=>{
  try{
    const p=message.payload||{};
    const base=cleanBase(p.base);
    const u=encodeURIComponent(p.username||"");
    const pw=encodeURIComponent(p.password||"");
    if(!base||!u||!pw) throw new Error("Providergegevens ontbreken");
    let url=base+"/player_api.php?username="+u+"&password="+pw;
    if(p.action&&p.action!=="auth") url+="&action="+encodeURIComponent(p.action);
    if(p.category_id) url+="&category_id="+encodeURIComponent(p.category_id);
    if(p.series_id) url+="&series_id="+encodeURIComponent(p.series_id);
    if(p.vod_id) url+="&vod_id="+encodeURIComponent(p.vod_id);
    if(p.stream_id) url+="&stream_id="+encodeURIComponent(p.stream_id);
    const txt=await getText(url);
    const data=JSON.parse(txt);
    if(p.action==="auth"&&(!data.user_info||String(data.user_info.auth)!=="1")) throw new Error("Inloggegevens niet geaccepteerd");
    message.respond({returnValue:true,ok:true,data:data});
  }catch(e){
    message.respond({returnValue:false,ok:false,error:String(e.message||e)});
  }
});

service.register("m3u",async message=>{
  try{
    const url=String((message.payload||{}).url||"");
    if(!/^https?:\/\//i.test(url)) throw new Error("Ongeldige M3U URL");
    const txt=await getText(url);
    const lines=txt.split(/\r?\n/);
    const out=[];
    let meta=null;
    lines.forEach(line=>{
      line=line.trim();
      if(line.indexOf("#EXTINF:")===0){
        const parts=line.split(",");
        const name=(parts.length?parts[parts.length-1]:"Kanaal").trim();
        const m=line.match(/tvg-logo="([^"]*)"/);
        meta={name:name,stream_icon:m?m[1]:""};
      }else if(meta&&/^https?:\/\//i.test(line)){
        out.push(Object.assign(meta,{url:line,stream_id:out.length+1}));
        meta=null;
      }
    });
    message.respond({returnValue:true,ok:true,data:out});
  }catch(e){
    message.respond({returnValue:false,ok:false,error:String(e.message||e)});
  }
});
