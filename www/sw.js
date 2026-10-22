/* Prodigy Syncing · service worker V90 · S.O.I / Bojack Security */
const V='prodigy-v90',CDN='prodigy-cdn',SHELL=['./','index.html','manifest.json','icon-192.png','icon-512.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(SHELL)).catch(()=>{}));self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V&&x!==CDN).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{const r=e.request,u=new URL(r.url);
  if(r.method==='GET'&&/(^|\.)(jsdelivr\.net|unpkg\.com|cdnjs\.cloudflare\.com)$/.test(u.host)&&/xterm|leaflet|pdf\.js|mammoth|xlsx/.test(u.pathname)){e.respondWith(caches.match(r).then(m=>m||fetch(r).then(x=>{if(x.ok||x.type==='opaque'){const c=x.clone();caches.open(CDN).then(k=>k.put(r,c))}return x})));return}
  if(r.method!=='GET'||u.origin!==location.origin)return;
  e.respondWith(fetch(r).then(x=>{if(x.ok){const c=x.clone();caches.open(V).then(k=>k.put(r,c))}return x}).catch(()=>caches.match(r).then(m=>m||caches.match('index.html'))))});
self.addEventListener('notificationclick',e=>{const a=e.action;e.notification.close();
  e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(cl=>{
    if(a&&cl.length)cl.forEach(c=>c.postMessage({action:a}));
    if(!a){return cl.length?cl[0].focus():self.clients.openWindow('./')}
    else if(cl.length===0)return self.clients.openWindow('./')}))});
