/* ============================================================
 *   Interfaz web, embebida en el firmware
 *   ============================================================
 *   Va como cadena en flash y no en una particion de datos: asi la
 *   pagina viaja siempre junto al codigo que la sirve, y no existe
 *   el caso de un firmware nuevo sirviendo una interfaz vieja que
 *   quedo de una carga anterior.
 *   ============================================================ */

#pragma once

static const char PAGINA_HTML[] = R"HTMLPAGE(
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<title>Smart Car 03</title>
<style>
  :root{
    --fondo:#05070f; --panel:#0b1224; --borde:#1e2c4a;
    --cian:#00e5ff; --azul:#2979ff; --ok:#00e676; --alerta:#ff3d5a; --ambar:#ffb300;
    --texto:#e6f3ff; --tenue:#7a8ca8;
  }
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  html,body{margin:0;height:100%;overflow:hidden;background:var(--fondo);
    color:var(--texto);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
    touch-action:none;user-select:none}
  #radar{position:absolute;inset:0;width:100%;height:100%}
  .barra{position:absolute;top:0;left:0;right:0;display:flex;align-items:center;
    gap:8px;padding:8px 12px;z-index:3}
  .chip{display:flex;align-items:center;gap:7px;padding:5px 11px;border-radius:99px;
    background:var(--panel);border:1px solid var(--borde);font-size:11px;
    letter-spacing:1px;font-weight:600;white-space:nowrap}
  .punto{width:8px;height:8px;border-radius:50%;background:var(--tenue)}
  .lleno{flex:1}
  .boton{padding:8px 14px;border-radius:10px;background:rgba(0,229,255,.12);
    border:1px solid rgba(0,229,255,.5);color:var(--cian);font-size:11px;
    letter-spacing:1.5px;font-weight:700;cursor:pointer}
  .boton:active{background:rgba(0,229,255,.28)}
  .boton.paro{background:rgba(255,61,90,.12);border-color:rgba(255,61,90,.5);
    color:var(--alerta)}
  .stick{position:absolute;bottom:12px;width:148px;height:148px;z-index:2}
  #stickIzq{left:12px} #stickDer{right:12px}
  .rot{position:absolute;bottom:0;font-size:9px;letter-spacing:2px;
    color:var(--tenue);font-weight:700;z-index:2}
  #rotIzq{left:12px;width:148px;text-align:center}
  #rotDer{right:12px;width:148px;text-align:center}
  #aviso{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);
    text-align:center;z-index:4;pointer-events:none;display:none}
  #aviso .t{font-size:12px;letter-spacing:3px;color:var(--cian);font-weight:700}
  #aviso .p{font-size:36px;font-weight:800;margin-top:4px}
  #datos{position:absolute;left:50%;bottom:14px;transform:translateX(-50%);
    font-size:10px;color:var(--tenue);letter-spacing:1px;z-index:2;text-align:center}
</style>
</head>
<body>
<canvas id="radar"></canvas>

<div class="barra">
  <div class="chip"><div class="punto" id="led"></div><span id="estado">CONECTANDO</span></div>
  <div class="lleno"></div>
  <div class="boton" id="btnEscanear">ESCANEAR</div>
  <div class="boton" id="btnLimpiar">LIMPIAR</div>
  <div class="boton paro" id="btnParo">PARO</div>
</div>

<canvas class="stick" id="stickIzq"></canvas><div class="rot" id="rotIzq">ACELERADOR</div>
<canvas class="stick" id="stickDer"></canvas><div class="rot" id="rotDer">DIRECCION</div>

<div id="datos">—</div>
<div id="aviso"><div class="t">ESCANEANDO</div><div class="p"><span id="pct">0</span>%</div></div>

<script>
"use strict";

const est = {
  ws:null, conectado:false, gas:0, giro:0,
  // Los ecos vivos llevan marca de tiempo para poder desvanecerlos: un punto
  // medido hace cuatro segundos ya no dice nada del entorno actual.
  ecos:[], escaneo:[], escaneando:false, ultAngulo:0, ultDist:-1
};

const ALCANCE_CM = 250;
const VIDA_MS = 4000;

// ---------- red ----------
function conectar(){
  est.ws = new WebSocket("ws://" + location.host + "/ws");
  est.ws.onopen = () => { est.conectado = true; pintarEstado(); };
  est.ws.onclose = () => {
    est.conectado = false; pintarEstado();
    // Reintento fijo: si el carro se reinicia, la pagina se reengancha sola sin
    // obligar a recargar desde el celular con las manos ocupadas.
    setTimeout(conectar, 1000);
  };
  est.ws.onmessage = (ev) => {
    let m; try { m = JSON.parse(ev.data); } catch(e) { return; }
    if (m.t === "s"){
      est.ultAngulo = m.a; est.ultDist = m.d;
      if (m.d > 0) est.ecos.push({a:m.a, d:m.d, t:performance.now()});
      if (est.ecos.length > 900) est.ecos.splice(0, 300);
    } else if (m.t === "p"){
      est.escaneando = true;
      document.getElementById("pct").textContent = m.v;
      document.getElementById("aviso").style.display = "block";
    } else if (m.t === "e"){
      est.escaneando = false;
      document.getElementById("aviso").style.display = "none";
      est.escaneo = m.pts || [];
    } else if (m.t === "ocupado"){
      // Otro mando esta conduciendo y aqui solo se mira. El carro lo repite
      // cada segundo mientras dure, asi que se anota la hora y el estado se
      // apaga solo cuando dejan de llegar.
      est.ocupado = performance.now();
    }
  };
}

function pintarEstado(){
  // Un aviso que se vence solo: sin el, quedarse con el cartel puesto despues
  // de que el otro solto el mando seria peor que no tenerlo.
  const espectador = est.ocupado && (performance.now() - est.ocupado) < 2500;

  document.getElementById("led").style.background =
    !est.conectado ? "var(--alerta)" : (espectador ? "var(--ambar)" : "var(--ok)");
  document.getElementById("estado").textContent =
    !est.conectado ? "SIN ENLACE" : (espectador ? "OTRO MANDO" : "ENLAZADO");
}

// El envio es periodico y no por evento: ese flujo constante alimenta el
// failsafe del carro, asi que cortarlo equivale a ordenar un frenado.
setInterval(() => {
  if (!est.conectado || est.escaneando) return;
  const m = mezclar(est.gas, est.giro);
  est.ws.send(JSON.stringify({t:"c", l:m.l, r:m.r}));
}, 50);

// El chip de estado se repinta solo porque el de espectador se vence por tiempo:
// nadie manda un "ya podes manejar", se deduce de que dejen de llegar avisos.
setInterval(pintarEstado, 500);

// ---------- mezcla ----------
// Curva expo: con respuesta lineal el tramo util del pulgar se gasta en la
// mitad alta del recorrido y maniobrar despacio se vuelve imposible.
function expo(v,k){ return k*v*v*v + (1-k)*v; }

function mezclar(gas, giro){
  const g = expo(gas, 0.55);
  const s = expo(giro, 0.60) * 0.65;   // autoridad recortada: curvas, no trompos
  let l = g + s, r = g - s;
  const pico = Math.max(Math.abs(l), Math.abs(r));
  // Normalizar y no recortar: recortar deformaria el radio de giro justo cuando
  // mas precision hace falta.
  if (pico > 1){ l /= pico; r /= pico; }
  return { l: Math.round(l*255), r: Math.round(r*255) };
}

// ---------- joysticks ----------
function joystick(canvas, eje, alMover){
  const ctx = canvas.getContext("2d");
  let px=0, py=0, activo=false, idDedo=null;
  const D=148, R=D/2, RK=R*0.30, REC=R-RK-6;

  function redibujar(){
    const dpr = devicePixelRatio || 1;
    canvas.width = D*dpr; canvas.height = D*dpr;
    ctx.setTransform(dpr,0,0,dpr,0,0);
    ctx.clearRect(0,0,D,D);
    ctx.beginPath(); ctx.arc(R,R,R-2,0,7);
    ctx.fillStyle="rgba(11,18,36,.75)"; ctx.fill();
    ctx.strokeStyle="rgba(0,229,255,.45)"; ctx.lineWidth=2; ctx.stroke();
    ctx.beginPath(); ctx.arc(R,R,R*0.6,0,7);
    ctx.strokeStyle="rgba(30,44,74,.9)"; ctx.lineWidth=1; ctx.stroke();
    const kx=R+px*REC, ky=R+py*REC;
    const g=ctx.createRadialGradient(kx-8,ky-9,2,kx,ky,RK);
    g.addColorStop(0,"#6fe9ff"); g.addColorStop(.6,"#00e5ff"); g.addColorStop(1,"#0d47a1");
    ctx.beginPath(); ctx.arc(kx,ky,RK,0,7); ctx.fillStyle=g; ctx.fill();
    ctx.strokeStyle="rgba(0,229,255,.9)"; ctx.lineWidth=2; ctx.stroke();
  }

  function fijar(cx,cy){
    const b=canvas.getBoundingClientRect();
    let dx=(cx-b.left-R)/REC, dy=(cy-b.top-R)/REC;
    if (eje==="y") dx=0;
    if (eje==="x") dy=0;
    const d=Math.hypot(dx,dy);
    if (d>1){ dx/=d; dy/=d; }
    px=dx; py=dy; redibujar(); alMover(px,-py);
  }

  canvas.addEventListener("touchstart", e=>{
    const t=e.changedTouches[0]; idDedo=t.identifier; activo=true;
    fijar(t.clientX,t.clientY); e.preventDefault();
  },{passive:false});
  canvas.addEventListener("touchmove", e=>{
    if(!activo) return;
    for(const t of e.changedTouches) if(t.identifier===idDedo) fijar(t.clientX,t.clientY);
    e.preventDefault();
  },{passive:false});
  function soltar(e){
    if(!activo) return;
    activo=false; idDedo=null; px=0; py=0; redibujar(); alMover(0,0); e.preventDefault();
  }
  canvas.addEventListener("touchend", soltar,{passive:false});
  canvas.addEventListener("touchcancel", soltar,{passive:false});

  // Raton, para poder probar desde el computador sin celular.
  canvas.addEventListener("mousedown", e=>{ activo=true; fijar(e.clientX,e.clientY); });
  window.addEventListener("mousemove", e=>{ if(activo) fijar(e.clientX,e.clientY); });
  window.addEventListener("mouseup", ()=>{
    if(!activo) return; activo=false; px=0; py=0; redibujar(); alMover(0,0);
  });

  redibujar();
}

joystick(document.getElementById("stickIzq"), "y", (x,y)=>{ est.gas=y; });
joystick(document.getElementById("stickDer"), "x", (x,y)=>{ est.giro=x; });

// ---------- radar ----------
const radar = document.getElementById("radar");
const rctx = radar.getContext("2d");
function ajustar(){
  const dpr = devicePixelRatio || 1;
  radar.width = innerWidth*dpr; radar.height = innerHeight*dpr;
  rctx.setTransform(dpr,0,0,dpr,0,0);
}
addEventListener("resize", ajustar); ajustar();

// El firmware ya entrega los angulos referidos al frente del carro: 0 es hacia
// adelante y crece hacia la derecha. Aqui solo se pasa a coordenadas de pantalla.
function aPantalla(gradosFrente, distCm, cx, cy, esc){
  const rad = gradosFrente * Math.PI/180;
  const r = (distCm/ALCANCE_CM)*esc;
  return { x: cx + r*Math.sin(rad), y: cy - r*Math.cos(rad) };
}

function dibujar(){
  const w=innerWidth, h=innerHeight;
  // Con un escaneo cargado el dibujo cubre mas de media vuelta, asi que el
  // carro baja al centro y el radio se achica para que entre completo.
  const hayEscaneo = est.escaneo.length > 0;
  const cx = w/2;
  const cy = hayEscaneo ? h*0.50 : h*0.62;
  const esc = hayEscaneo ? Math.min(w*0.30, h*0.38) : Math.min(w*0.32, h*0.44);

  rctx.clearRect(0,0,w,h);

  // Anillos de distancia, uno cada medio metro.
  rctx.strokeStyle="rgba(30,44,74,.85)"; rctx.lineWidth=1;
  for(let m=0.5; m<=2.5; m+=0.5){
    rctx.beginPath();
    const r=(m*100/ALCANCE_CM)*esc;
    if (hayEscaneo) rctx.arc(cx,cy,r,0,Math.PI*2);
    else rctx.arc(cx,cy,r,Math.PI,Math.PI*2);
    rctx.stroke();
  }
  rctx.fillStyle="rgba(122,140,168,.75)"; rctx.font="9px system-ui";
  for(let m=1;m<=2;m++) rctx.fillText(m+" m", cx+4, cy-(m*100/ALCANCE_CM)*esc-3);

  // Radios de referencia cada 30 grados.
  const desde = hayEscaneo ? -180 : -90;
  const hasta = hayEscaneo ?  180 :  90;
  for(let a=desde; a<=hasta; a+=30){
    const p=aPantalla(a, ALCANCE_CM, cx, cy, esc);
    rctx.beginPath(); rctx.moveTo(cx,cy); rctx.lineTo(p.x,p.y);
    rctx.strokeStyle="rgba(30,44,74,.55)"; rctx.stroke();
  }

  // Escaneo guardado: debajo de los ecos vivos y sin desvanecerse, porque
  // representa una medicion cerrada y no una lectura que envejece.
  if (hayEscaneo){
    rctx.strokeStyle="rgba(255,179,0,.35)"; rctx.lineWidth=1.5;
    rctx.beginPath();
    let primero=true;
    for(const p of est.escaneo){
      if(p.d<=0){ primero=true; continue; }
      const q=aPantalla(p.a,p.d,cx,cy,esc);
      if(primero){ rctx.moveTo(q.x,q.y); primero=false; } else rctx.lineTo(q.x,q.y);
    }
    rctx.stroke();

    rctx.fillStyle="rgba(255,179,0,.9)";
    for(const p of est.escaneo){
      if(p.d<=0) continue;
      const q=aPantalla(p.a,p.d,cx,cy,esc);
      rctx.beginPath(); rctx.arc(q.x,q.y,2.5,0,7); rctx.fill();
    }
  }

  // Ecos vivos, apagandose con la edad.
  const ahora=performance.now();
  est.ecos = est.ecos.filter(e => ahora-e.t < VIDA_MS);
  for(const e of est.ecos){
    const vida = 1-(ahora-e.t)/VIDA_MS;
    const p = aPantalla(e.a, e.d, cx, cy, esc);
    rctx.beginPath(); rctx.arc(p.x,p.y,3,0,7);
    rctx.fillStyle="rgba(0,229,255,"+(vida*0.9).toFixed(2)+")"; rctx.fill();
  }

  // Haz actual.
  const hz=aPantalla(est.ultAngulo, ALCANCE_CM, cx, cy, esc);
  const grad=rctx.createLinearGradient(cx,cy,hz.x,hz.y);
  grad.addColorStop(0,"rgba(0,229,255,.55)"); grad.addColorStop(1,"rgba(0,229,255,0)");
  rctx.beginPath(); rctx.moveTo(cx,cy); rctx.lineTo(hz.x,hz.y);
  rctx.strokeStyle=grad; rctx.lineWidth=3; rctx.stroke();

  // El carro, con la nariz marcando hacia donde apunta el frente.
  rctx.beginPath(); rctx.arc(cx,cy,7,0,7);
  rctx.fillStyle="#2979ff"; rctx.fill();
  rctx.strokeStyle="#00e5ff"; rctx.lineWidth=2; rctx.stroke();
  rctx.beginPath(); rctx.moveTo(cx,cy-7); rctx.lineTo(cx,cy-15);
  rctx.strokeStyle="#00e5ff"; rctx.stroke();

  document.getElementById("datos").textContent =
    est.ultDist>0 ? est.ultAngulo+"°   "+est.ultDist.toFixed(0)+" cm"
                  : est.ultAngulo+"°   sin eco";

  requestAnimationFrame(dibujar);
}
requestAnimationFrame(dibujar);

// ---------- botones ----------
document.getElementById("btnEscanear").onclick = () => {
  if (est.conectado && !est.escaneando) est.ws.send(JSON.stringify({t:"scan"}));
};
document.getElementById("btnLimpiar").onclick = () => { est.ecos=[]; est.escaneo=[]; };
document.getElementById("btnParo").onclick = () => {
  est.gas=0; est.giro=0;
  if (est.conectado) est.ws.send(JSON.stringify({t:"stop"}));
};

conectar(); pintarEstado();
</script>
</body>
</html>
)HTMLPAGE";
