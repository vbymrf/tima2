package io.tima.core.media

/**
 * Страницы браузерного движка (ПЛАН-(ВМ) ВМ6): проигрыватель сайта или `<video>` и мост к окну.
 *
 * Мост наружу — `send({t, v, d})`: `time` (секунды, длительность), `state` (`playing`, `paused`,
 * `ended`, `buffering`), `error` (код сайта), `ready`. Внутрь окно зовёт `tima.play()`,
 * `tima.pause()`, `tima.seek(сек)`. Android передаёт через `TimaBridge.post`, WebView2 — через
 * `chrome.webview.postMessage`.
 *
 * Управление чужими проигрывателями — их собственными описанными средствами: YouTube — IFrame
 * Player API, Rutube — `postMessage` своего проигрывателя, VK — `VK.VideoPlayer`. Не отвечает — окно
 * перематывает перезагрузкой с секундой в адресе.
 */
object WebPages {

    private const val BRIDGE = """
function send(o){var s=JSON.stringify(o);
 if(window.TimaBridge&&TimaBridge.post){TimaBridge.post(s);}
 else if(window.chrome&&chrome.webview){chrome.webview.postMessage(s);}}
window.onerror=function(m){send({t:'error',v:String(m).slice(0,120)});};
"""

    private fun frame(body: String, script: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe,video,#p{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
</head><body>$body<script>$BRIDGE$script</script></body></html>"""

    private fun js(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c") + "'"

    /** Прямой файл: `<video>` — кодеки Chromium (WebM, VP9, AV1, Opus), которых нет у Media Foundation. */
    fun file(url: String, startSec: Int): WebPage = WebPage(
        WebPage.Kind.FILE, url,
        html = frame(
            """<video id="v" autoplay playsinline></video>""",
            """
var v=document.getElementById('v');v.src=${js(url)};
v.addEventListener('loadedmetadata',function(){if($startSec>0)v.currentTime=$startSec;send({t:'ready'});
 var p=v.play();if(p&&p.catch)p.catch(function(e){send({t:'error',v:'play '+e.name});});});
v.addEventListener('timeupdate',function(){send({t:'time',v:v.currentTime,d:isFinite(v.duration)?v.duration:0});});
v.addEventListener('playing',function(){send({t:'state',v:'playing'});});
v.addEventListener('pause',function(){send({t:'state',v:'paused'});});
v.addEventListener('waiting',function(){send({t:'state',v:'buffering'});});
v.addEventListener('ended',function(){send({t:'state',v:'ended'});});
v.addEventListener('error',function(){send({t:'error',v:'media '+(v.error?v.error.code:0)});});
window.tima={play:function(){v.play();},pause:function(){v.pause();},seek:function(s){v.currentTime=s;},mute:function(m){v.muted=!!m;}};
""",
        ),
    )

    /** YouTube — IFrame Player API (Р14); коды ошибок YouTube: 101 и 150 — встраивание запрещено автором. */
    fun youtube(url: String, id: String, startSec: Int): WebPage = WebPage(
        WebPage.Kind.YOUTUBE, url, videoId = id, sites = listOf("youtube.com", "youtube-nocookie.com"), key = id,
        html = frame(
            """<div id="p"></div>""",
            """
var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';document.head.appendChild(tag);
var player;
function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:${js(id)},
 playerVars:{autoplay:1,playsinline:1,start:$startSec,rel:0,origin:${js(WebPage.ORIGIN.trimEnd('/'))}},
 events:{onReady:function(){send({t:'ready'});applyM();player.playVideo();},
  onStateChange:function(e){var m={'-1':'buffering','0':'ended','1':'playing','2':'paused','3':'buffering'};send({t:'state',v:m[String(e.data)]||'paused'});
   var vd=player.getVideoData&&player.getVideoData();if(vd&&vd.video_id&&vd.video_id!==${js(id)})send({t:'away'});},
  onError:function(e){send({t:'error',v:'youtube '+e.data});}}});}
setInterval(function(){if(player&&player.getCurrentTime)send({t:'time',v:player.getCurrentTime(),d:player.getDuration()});},500);
var M=false;function applyM(){if(player&&player.mute){if(M)player.mute();else player.unMute();}}
window.tima={play:function(){player&&player.playVideo();},pause:function(){player&&player.pauseVideo();},seek:function(s){player&&player.seekTo(s,true);},mute:function(m){M=!!m;applyM();}};
""",
        ),
    )

    /** Rutube — их проигрыватель и его `postMessage` (`player:play`, `player:setCurrentTime`…). */
    fun rutube(url: String, id: String, startSec: Int): WebPage = WebPage(
        WebPage.Kind.RUTUBE, url, sites = listOf("rutube.ru"), key = id,
        html = frame(
            """<iframe id="f" allow="autoplay; fullscreen; encrypted-media" allowfullscreen src="https://rutube.ru/play/embed/$id?autoplay=true${if (startSec > 0) "&t=$startSec" else ""}"></iframe>""",
            """
var f=document.getElementById('f');
function cmd(type,data){f.contentWindow.postMessage(JSON.stringify({type:type,data:data||{}}),'*');}
var M=false;function applyM(){cmd(M?'player:mute':'player:unMute');}
window.addEventListener('message',function(e){var m;try{m=typeof e.data==='string'?JSON.parse(e.data):e.data;}catch(x){return;}
 if(!m||!m.type)return;var d=m.data||{};
 if(m.type==='player:ready'){send({t:'ready'});applyM();}
 else if(m.type==='player:currentTime')send({t:'time',v:d.time||0,d:d.duration||0});
 else if(m.type==='player:durationChange')send({t:'time',v:0,d:d.duration||0});
 else if(m.type==='player:changeState'){var s=d.state;send({t:'state',v:s==='playing'?'playing':s==='stopped'||s==='ended'?'ended':'paused'});}
 else if(m.type==='player:error')send({t:'error',v:'rutube '+(d.code||'')});
});
window.tima={play:function(){cmd('player:play');},pause:function(){cmd('player:pause');},seek:function(s){cmd('player:setCurrentTime',{time:s});},mute:function(m){M=!!m;applyM();}};
""",
        ),
    )

    /** VK Видео — их проигрыватель и `VK.VideoPlayer`; нет его — только показ, перемотка перезагрузкой. */
    fun vk(url: String, oid: String, id: String, hash: String, startSec: Int): WebPage {
        val t = if (startSec > 0) "&t=${startSec / 60}m${startSec % 60}s" else ""
        return WebPage(
            WebPage.Kind.VK, url, sites = listOf("vkvideo.ru", "vk.com", "vk.ru"), key = id,
            html = frame(
                """<iframe id="f" allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen src="https://vkvideo.ru/video_ext.php?oid=$oid&id=$id&hash=$hash&autoplay=1&js_api=1$t"></iframe>""",
                """
var p=null;var f=document.getElementById('f');
// VK запускает встроенное видео без звука — включаем, когда пошло (заказчик 2026-10-23).
var M=false;function applyM(){if(!p)return;try{if(M)p.mute();else{p.unmute();p.setVolume(1);}}catch(x){}}
var s=document.createElement('script');s.src='https://vk.com/js/api/videoplayer.js';
s.onload=function(){try{p=VK.VideoPlayer(f);var E=VK.VideoPlayer.Events;
 p.on(E.INITED,function(){send({t:'ready'});applyM();});
 p.on(E.TIMEUPDATE,function(e){send({t:'time',v:e.time||p.getCurrentTime(),d:p.getDuration()});});
 p.on(E.STARTED,function(){send({t:'state',v:'playing'});applyM();});p.on(E.RESUMED,function(){send({t:'state',v:'playing'});});
 p.on(E.PAUSED,function(){send({t:'state',v:'paused'});});p.on(E.ENDED,function(){send({t:'state',v:'ended'});});
 p.on(E.ERROR,function(e){send({t:'error',v:'vk '+(e&&e.code||'')});});}catch(x){send({t:'error',v:'vk api'});}};
document.head.appendChild(s);
window.tima={play:function(){p&&p.play();},pause:function(){p&&p.pause();},seek:function(s){p&&p.seek(s);},mute:function(m){M=!!m;applyM();}};
""",
            ),
        )
    }
}
