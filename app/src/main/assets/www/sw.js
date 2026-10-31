const C='wz13',CDN=['https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js','https://cdnjs.cloudflare.com/ajax/libs/peerjs/1.5.4/peerjs.min.js'];
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(C).then(c=>Promise.all([c.add('./index.html').catch(()=>0),c.add('./three.min.js').catch(()=>0)].concat(CDN.map(u=>fetch(u,{mode:'no-cors'}).then(r=>c.put(u,r)).catch(()=>0))))))});
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==C).map(x=>caches.delete(x)))).then(()=>clients.claim())));
self.addEventListener('fetch',e=>{const r=e.request,u=new URL(r.url);if(r.method!=='GET'||u.pathname.startsWith('/pay/'))return;
  const same=u.origin===location.origin,cdn=CDN.includes(r.url)||u.host==='fonts.googleapis.com'||u.host==='fonts.gstatic.com';
  if(!same&&!cdn)return; // لا نعترض طلبات PeerJS للأونلاين
  e.respondWith(same?fetch(r,{cache:'reload'}).then(x=>{if(x.ok){const c=x.clone();caches.open(C).then(k=>k.put(r,c))}return x}).catch(()=>caches.match(r).then(x=>x||caches.match('./index.html'))):caches.match(r).then(x=>x||fetch(r).then(y=>{const c=y.clone();caches.open(C).then(k=>k.put(r,c)).catch(()=>0);return y})))});
