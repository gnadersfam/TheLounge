package com.lounge.tv

/** The phone remote page. It is plain HTML and JavaScript, served by the Firestick to your phone over your own Wi-Fi. */
object RemotePage {
    val HTML: String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name="theme-color" content="#07030F">
<title>Lounge Remote</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#07030F;color:#F5EEFF;font:16px system-ui,-apple-system,Segoe UI,Roboto,sans-serif;-webkit-user-select:none;user-select:none;touch-action:manipulation}
#wrap{max-width:430px;margin:0 auto;padding:22px 18px 40px;display:flex;flex-direction:column;gap:16px;background:radial-gradient(ellipse at 50% 0%,#2a0f4d 0%,#07030F 45%)}
button{font:inherit;color:inherit;cursor:pointer}
.hdr{display:flex;align-items:center;justify-content:space-between}
.logo{font-size:22px;font-weight:700;letter-spacing:2px;color:#19F0FF}
.sub{font-size:13px;color:#C9B8F0}
.pill{display:flex;align-items:center;gap:8px;padding:6px 12px;border-radius:999px;background:#0E0820;border:1px solid #3A2468;font-size:13px}
.dot{width:9px;height:9px;border-radius:50%;background:#3DDC97}
.dot.off{background:#FF5C7A}
.awake{display:flex;align-items:center;gap:8px;font-size:12px;color:#C9B8F0;margin-top:6px;justify-content:flex-end}
.sw{width:34px;height:18px;border-radius:9px;background:#3A2468;position:relative;display:inline-block;border:0;padding:0}
.sw:after{content:"";position:absolute;left:2px;top:2px;width:14px;height:14px;border-radius:50%;background:#C9B8F0}
.sw.on{background:#19F0FF}
.sw.on:after{left:auto;right:2px;background:#07030F}
.cap{font-size:11px;letter-spacing:2px;color:#19F0FF;font-weight:600}
.cap2{font-size:11px;letter-spacing:2px;color:#C9B8F0;margin-bottom:8px}
#scores{display:flex;gap:10px;overflow-x:auto;padding-bottom:4px}
.sc{flex:0 0 auto;min-width:150px;text-align:left;border-radius:14px;border:1px solid #3A2468;background:#1A1030;padding:10px 12px}
.sc.live{border-color:#FF3DDB}
.sc .st{font-size:12px;color:#C9B8F0}
.sc.live .st{color:#FF5C7A;font-weight:600}
.sc .tt{font-size:16px;font-weight:600;margin-top:2px}
.np{border-radius:16px;background:#1A1030;border:1px solid #3A2468;padding:12px 16px 14px}
.np .t{font-size:18px;font-weight:600;margin-top:2px}
.np .s{font-size:13px;color:#C9B8F0;margin-top:2px;min-height:17px}
input[type=range]{width:100%;margin:10px 0 0;accent-color:#19F0FF;height:28px;background:transparent}
.times{display:flex;justify-content:space-between;font-size:13px;color:#C9B8F0}
.pad{width:264px;height:264px;margin:0 auto;border-radius:50%;background:#0E0820;border:2px solid #3A2468;display:grid;grid-template-columns:repeat(3,1fr);grid-template-rows:repeat(3,1fr);padding:8px}
.pad button{border:0;background:transparent;display:flex;align-items:center;justify-content:center;border-radius:50%}
.pad button:active{background:#2A1450}
.pad svg{width:30px;height:30px;stroke:#19F0FF;fill:none;stroke-width:2.5;stroke-linecap:round;stroke-linejoin:round}
.ok{border:3px solid #19F0FF!important;background:#2A1450!important;font-size:20px;font-weight:700;box-shadow:0 0 0 5px rgba(255,61,219,.35)}
.row{display:flex;gap:12px}
.btn{flex:1;height:48px;border-radius:14px;border:1px solid #3A2468;background:#1A1030;font-size:16px;font-weight:600}
.btn:active{background:#2A1450}
.prim{background:#19F0FF;color:#07030F;border:0;flex:1.4;font-weight:700}
.vol{display:flex;gap:10px;align-items:center}
.vol .btn{flex:0 0 auto;width:56px;height:52px;font-size:24px}
.vol .mute{width:64px;font-size:15px;border-color:#FF5C7A;color:#FF5C7A}
.vol .mute.on{background:#FF5C7A;color:#07030F}
.bar{height:8px;border-radius:4px;background:rgba(255,255,255,.18)}
.bar i{display:block;height:8px;border-radius:4px;background:#19F0FF;width:0}
.vm{flex:1}
.vm div{text-align:center;font-size:12px;color:#C9B8F0;margin-top:6px}
.ch{display:flex;gap:10px;align-items:center}
.ch .btn{flex:0 0 84px;height:56px;font-size:15px}
.chn{flex:1;height:56px;border-radius:14px;background:#0E0820;border:1px solid #3A2468;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:0 6px}
.chn b{font-size:15px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}
.chn span{font-size:12px;color:#C9B8F0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%}
.srch{display:flex;gap:10px}
.srch input{flex:1;height:48px;border-radius:14px;border:1px solid #3A2468;background:#0E0820;color:#F5EEFF;font-size:16px;padding:0 14px;-webkit-user-select:text;user-select:text;min-width:0}
.srch .go{width:92px;height:48px;border-radius:14px;border:0;background:#FF3DDB;color:#07030F;font-weight:700;font-size:16px}
.pages{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}
.pages button{height:44px;border-radius:12px;border:1px solid #3A2468;background:#1A1030;color:#C9B8F0;font-size:14px}
#res{display:none;max-width:430px;margin:0 auto;padding:22px 18px 40px}
.rhead{display:flex;gap:12px;align-items:center;margin-bottom:12px}
.rhead .back{width:44px;height:44px;border-radius:12px;border:1px solid #3A2468;background:#1A1030;font-size:18px}
.rhead input{flex:1;height:48px;border-radius:14px;border:2px solid #19F0FF;background:#0E0820;color:#F5EEFF;font-size:18px;padding:0 14px;min-width:0;-webkit-user-select:text;user-select:text}
.sec{margin-top:18px}
.item{width:100%;display:flex;align-items:center;gap:12px;text-align:left;border-radius:14px;border:1px solid #3A2468;background:#1A1030;padding:10px 14px;margin-bottom:8px}
.item.l{border-color:#FF3DDB}
.item b{display:block;font-size:16px;font-weight:600}
.item span{font-size:13px;color:#C9B8F0}
.item em{margin-left:auto;font-style:normal;font-size:13px;color:#19F0FF;font-weight:600;white-space:nowrap}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#2A1450;border:1px solid #19F0FF;color:#F5EEFF;padding:10px 18px;border-radius:12px;font-size:15px;display:none;z-index:9;max-width:90%;text-align:center}
</style></head><body>
<div id="wrap">
  <div class="hdr">
    <div><div class="logo">THE LOUNGE</div><div class="sub">Remote</div></div>
    <div>
      <div class="pill"><span class="dot" id="dot"></span><span id="conn">Connected</span></div>
      <div class="awake">Keep screen on <button class="sw on" id="awake" aria-label="Keep screen on"></button></div>
    </div>
  </div>

  <div>
    <div class="cap" style="margin-bottom:8px">FOOTBALL &middot; TODAY <span class="sub" style="font-weight:400;letter-spacing:0">&nbsp;tap to watch</span></div>
    <div id="scores"><div class="sc"><div class="st">Loading scores...</div></div></div>
  </div>

  <div class="np">
    <div class="cap">NOW PLAYING</div>
    <div class="t" id="nt">Nothing playing</div>
    <div class="s" id="ns"></div>
    <input type="range" id="seek" min="0" max="1000" value="0" disabled aria-label="Skip bar">
    <div class="times"><span id="tp">0:00</span><span id="th">Drag to skip</span><span id="td">0:00</span></div>
  </div>

  <div class="pad">
    <div></div>
    <button data-k="up" data-r="1" aria-label="Up"><svg viewBox="0 0 24 24"><path d="M6 15l6-6 6 6"/></svg></button>
    <div></div>
    <button data-k="left" data-r="1" aria-label="Left"><svg viewBox="0 0 24 24"><path d="M15 6l-6 6 6 6"/></svg></button>
    <button data-k="ok" class="ok" aria-label="OK">OK</button>
    <button data-k="right" data-r="1" aria-label="Right"><svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg></button>
    <div></div>
    <button data-k="down" data-r="1" aria-label="Down"><svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>
    <div></div>
  </div>

  <div class="row">
    <button class="btn" data-k="back">Back</button>
    <button class="btn" data-p="home">Home</button>
    <button class="btn" data-k="menu">Menu</button>
  </div>

  <div class="row">
    <button class="btn" data-k="rew" data-r="1">-10 s</button>
    <button class="btn prim" data-k="pp" id="pp">Play / Pause</button>
    <button class="btn" data-k="ff" data-r="1">+10 s</button>
  </div>

  <div>
    <div class="cap2">VOLUME</div>
    <div class="vol">
      <button class="btn mute" id="mute" data-v="mute">Mute</button>
      <button class="btn" data-v="down" data-r="1" aria-label="Volume down">&minus;</button>
      <div class="vm"><div class="bar"><i id="vbar"></i></div><div id="vtxt">0%</div></div>
      <button class="btn" data-v="up" data-r="1" aria-label="Volume up">+</button>
    </div>
  </div>

  <div>
    <div class="cap2">LIVE TV CHANNEL</div>
    <div class="ch">
      <button class="btn" data-c="down">CH &minus;</button>
      <div class="chn"><b id="cn">No channel</b><span id="cs"></span></div>
      <button class="btn" data-c="up">CH +</button>
    </div>
  </div>

  <div class="srch">
    <input id="q" type="search" placeholder="Search channels, films, shows" autocomplete="off" autocapitalize="none">
    <button class="go" id="go">Search</button>
  </div>

  <div class="pages">
    <button data-p="live">Live TV</button>
    <button data-p="movies">Movies</button>
    <button data-p="shows">Shows</button>
    <button data-p="football">Football</button>
  </div>
</div>

<div id="res">
  <div class="rhead"><button class="back" id="back" aria-label="Back to the remote">&larr;</button><input id="q2" type="search" autocomplete="off" autocapitalize="none"></div>
  <div class="sub" id="rnote">Tap a result and it starts on the TV</div>
  <div id="rbody"></div>
</div>

<div id="toast"></div>
<video id="nosleep" muted loop playsinline style="position:fixed;left:0;top:0;width:1px;height:1px;opacity:0.01"></video>

<script>
var PIN='__PIN__';
var byId=function(id){return document.getElementById(id);};
function enc(s){return encodeURIComponent(s);}
function buzz(){try{if(navigator.vibrate)navigator.vibrate(12);}catch(e){}}
function toast(t){var el=byId('toast');el.textContent=t;el.style.display='block';clearTimeout(toast.t);toast.t=setTimeout(function(){el.style.display='none';},2200);}
function setConn(ok){byId('dot').className=ok?'dot':'dot off';byId('conn').textContent=ok?'Connected':'No signal';}
function api(path,data,cb){
  var x=new XMLHttpRequest();
  var q=(path.indexOf('?')>=0?'&':'?')+'k='+PIN;
  if(data){
    x.open('POST',path+q);
    x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
    var b=[];for(var k in data)b.push(enc(k)+'='+enc(data[k]));
    x.send(b.join('&'));
  }else{x.open('GET',path+q);x.send();}
  x.onreadystatechange=function(){
    if(x.readyState==4){
      var r=null;try{r=JSON.parse(x.responseText);}catch(e){}
      setConn(x.status==200);
      if(cb)cb(r);
    }
  };
}
function hold(el,fn,rep){
  var t=null,iv=null;
  function stop(){if(t){clearTimeout(t);t=null;}if(iv){clearInterval(iv);iv=null;}}
  el.addEventListener('pointerdown',function(e){
    e.preventDefault();buzz();fn();
    if(rep){t=setTimeout(function(){iv=setInterval(fn,160);},420);}
  });
  ['pointerup','pointerleave','pointercancel'].forEach(function(n){el.addEventListener(n,stop);});
}
function bind(sel,attr,fn){
  var els=document.querySelectorAll(sel);
  for(var i=0;i<els.length;i++){(function(el){
    hold(el,function(){fn(el.getAttribute(attr));},el.getAttribute('data-r')==='1');
  })(els[i]);}
}
bind('[data-k]','data-k',function(k){api('/k',{key:k});});
bind('[data-v]','data-v',function(v){api('/vol',{d:v},function(){poll();});});
bind('[data-c]','data-c',function(c){api('/ch',{d:c},function(r){if(r&&r.msg)toast(r.msg);setTimeout(poll,500);});});
bind('[data-p]','data-p',function(p){api('/open',{p:p});});

function fmt(ms){
  var s=Math.max(0,Math.floor(ms/1000)),h=Math.floor(s/3600),m=Math.floor((s%3600)/60),r=s%60;
  var two=function(n){return n<10?'0'+n:''+n;};
  return h>0?h+':'+two(m)+':'+two(r):m+':'+two(r);
}
var dragging=false;
var seek=byId('seek');
seek.addEventListener('input',function(){dragging=true;if(seek.dur)byId('tp').textContent=fmt(seek.dur*seek.value/1000);});
seek.addEventListener('change',function(){buzz();api('/seek',{ms:Math.round((seek.dur||0)*seek.value/1000)});setTimeout(function(){dragging=false;poll();},600);});

function apply(s){
  if(!s)return;
  var playing=!!s.playing;
  byId('nt').textContent=playing?(s.title||'Playing'):'Nothing playing';
  byId('ns').textContent=playing?(s.live?(s.sub||'Live TV'):(s.paused?'Paused':'')):(s.page?'On the TV: '+s.page:'');
  byId('pp').textContent=playing?(s.paused?'Play':'Pause'):'Play / Pause';
  var canSeek=playing&&!s.live&&s.dur>0;
  seek.disabled=!canSeek;
  seek.dur=s.dur||0;
  if(!dragging){
    seek.value=canSeek?Math.round(1000*s.pos/s.dur):0;
    byId('tp').textContent=canSeek?fmt(s.pos):'0:00';
  }
  byId('td').textContent=canSeek?fmt(s.dur):(s.live&&playing?'Live':'0:00');
  byId('th').textContent=canSeek?'Drag to skip':(s.live&&playing?'Live TV':'Skip bar');
  byId('vbar').style.width=s.fixed?'0%':((s.muted?0:s.vol)+'%');
  byId('vtxt').textContent=s.fixed?'Volume goes to the TV':(s.muted?'Muted':s.vol+'%');
  byId('mute').className=s.muted?'btn mute on':'btn mute';
  if(playing&&s.live){byId('cn').textContent=(s.num?s.num+' · ':'')+(s.title||'');byId('cs').textContent=s.sub||'';}
  else{byId('cn').textContent='No live channel';byId('cs').textContent=playing?'Playing a film or show':'Start a channel on the TV';}
}
function poll(){api('/s',null,apply);}
setInterval(poll,1500);poll();

function renderScores(r){
  var box=byId('scores');box.innerHTML='';
  var list=(r&&r.list)||[];
  if(!list.length){box.innerHTML='<div class="sc"><div class="st">No matches today</div></div>';return;}
  list.forEach(function(m){
    var b=document.createElement('button');
    b.className='sc'+(m.state==='in'?' live':'');
    var st=m.state==='in'?('LIVE '+m.clock):(m.state==='post'?'Full time':'');
    if(m.state==='pre'){try{st=new Date(m.when).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});}catch(e){st='Today';}}
    var line=m.state==='pre'?(m.hn+' v '+m.an):(m.hn+' '+m.hg+' - '+m.ag+' '+m.an);
    b.innerHTML='<div class="st"></div><div class="tt"></div>';
    b.firstChild.textContent=st;b.lastChild.textContent=line;
    b.addEventListener('click',function(){buzz();api('/watch',{home:m.home,away:m.away},function(r){toast(r&&r.msg?r.msg:'Looking for the channel...');});});
    box.appendChild(b);
  });
}
function scoresNow(){api('/scores',null,renderScores);}
scoresNow();setInterval(scoresNow,30000);

function show(res){byId('wrap').style.display=res?'none':'flex';byId('res').style.display=res?'block':'none';window.scrollTo(0,0);}
function section(title,items,kind){
  if(!items||!items.length)return '';
  var h='<div class="sec"><div class="cap" style="margin-bottom:8px">'+title+'</div>';
  items.forEach(function(it,i){
    h+='<button class="item'+(kind==='live'?' l':'')+'" data-t="'+kind+'" data-id="'+String(it.id).replace(/"/g,'')+'"><div><b></b><span></span></div><em>'+(kind==='series'?'Open on TV':'Play on TV')+'</em></button>';
  });
  return h+'</div>';
}
function runSearch(q){
  q=(q||'').trim();
  if(q.length<2){toast('Type at least 2 letters');return;}
  byId('q2').value=q;show(true);
  byId('rbody').innerHTML='<div class="sub" style="margin-top:16px">Searching...</div>';
  api('/find?q='+enc(q),null,function(r){
    if(!r){byId('rbody').innerHTML='<div class="sub" style="margin-top:16px">Could not search. Try again.</div>';return;}
    var html=section('LIVE TV &middot; '+r.live.length,r.live,'live')+section('MOVIES &middot; '+r.movies.length,r.movies,'movie')+section('SHOWS &middot; '+r.series.length,r.series,'series');
    if(!html)html='<div class="sub" style="margin-top:16px">Nothing found for that.</div>';
    var body=byId('rbody');body.innerHTML=html;
    var btns=body.querySelectorAll('.item');
    var groups={live:r.live,movie:r.movies,series:r.series};var idx={live:0,movie:0,series:0};
    for(var i=0;i<btns.length;i++){(function(b){
      var kind=b.getAttribute('data-t');var it=groups[kind][idx[kind]++];
      b.querySelector('b').textContent=it.name;
      b.querySelector('span').textContent=it.sub||'';
      b.addEventListener('click',function(){buzz();api('/play',{t:kind,id:it.id},function(rr){toast(rr&&rr.msg?rr.msg:'Starting on the TV');if(rr&&rr.ok)setTimeout(function(){show(false);},900);});});
    })(btns[i]);}
  });
}
byId('go').addEventListener('click',function(){runSearch(byId('q').value);});
byId('q').addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();runSearch(byId('q').value);}});
byId('q2').addEventListener('keydown',function(e){if(e.key==='Enter'){e.preventDefault();runSearch(byId('q2').value);}});
byId('back').addEventListener('click',function(){show(false);});

var awake=true,lock=null,vid=byId('nosleep');
vid.src='data:video/mp4;base64,AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMsbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAlZ0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAEAAAABAAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAHObWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABeW1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAATlzdGJsAAAAuXN0c2QAAAAAAAAAAQAAAKlhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAQABIAAAASAAAAAAAAAABFUxhdmM2MC4zMS4xMDIgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAAL2F2Y0MBQsAK/+EAF2dCwArZBCbARAAAAwAEAAADABA8SJkgAQAFaMuDyyAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAUyAAAFMgAAAAYc3R0cwAAAAAAAAABAAAAAgAAIAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxzdHNjAAAAAAAAAAEAAAABAAAAAgAAAAEAAAAcc3RzegAAAAAAAAAAAAAAAgAAAo8AAAAKAAAAFHN0Y28AAAAAAAAAAQAAA1wAAABidWR0YQAAAFptZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAAC1pbHN0AAAAJal0b28AAAAdZGF0YQAAAAEAAAAATGF2ZjYwLjE2LjEwMAAAAAhmcmVlAAACoW1kYXQAAAJwBgX//2zcRem95tlIt5Ys2CDZI+7veDI2NCAtIGNvcmUgMTY0IHIzMTA4IDMxZTE5ZjkgLSBILjI2NC9NUEVHLTQgQVZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDIzIC0gaHR0cDovL3d3dy52aWRlb2xhbi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MCByZWY9MyBkZWJsb2NrPTE6MDowIGFuYWx5c2U9MHgxOjB4MTExIG1lPWhleCBzdWJtZT03IHBzeT0xIHBzeV9yZD0xLjAwOjAuMDAgbWl4ZWRfcmVmPTEgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEgdHJlbGxpcz0xIDh4OGRjdD0wIGNxbT0wIGRlYWR6b25lPTIxLDExIGZhc3RfcHNraXA9MSBjaHJvbWFfcXBfb2Zmc2V0PS0yIHRocmVhZHM9MiBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0yNTAga2V5aW50X21pbj0yIHNjZW5lY3V0PTQwIGludHJhX3JlZnJlc2g9MCByY19sb29rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAAAF2WIhAU8mKAAP78nJyddddddddddddeAAAAABkGaOAl4Rg==';
function keepAwake(on){
  awake=on;byId('awake').className=on?'sw on':'sw';
  try{localStorage.setItem('lounge_awake',on?'1':'0');}catch(e){}
  try{
    if(on){
      if(navigator.wakeLock){navigator.wakeLock.request('screen').then(function(l){lock=l;},function(){});}
      var p=vid.play();if(p&&p.catch)p.catch(function(){});
    }else{
      if(lock){lock.release();lock=null;}
      vid.pause();
    }
  }catch(e){}
}
byId('awake').addEventListener('click',function(){keepAwake(!awake);});
document.addEventListener('visibilitychange',function(){if(!document.hidden&&awake)keepAwake(true);});
document.addEventListener('pointerdown',function(){if(awake&&vid.paused){try{vid.play();}catch(e){}}});
try{if(localStorage.getItem('lounge_awake')==='0')awake=false;}catch(e){}
keepAwake(awake);
</script>
</body></html>"""
}
