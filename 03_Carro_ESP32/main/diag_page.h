/* ============================================================
 *   Pantalla de diagnostico, embebida en el firmware
 *   ============================================================
 *   Es el monitor serie, pero por WiFi. Se sirve en /diag y no en
 *   la raiz porque la raiz es el mando: durante una demostracion
 *   nadie quiere aterrizar en una pantalla de log.
 *
 *   Se refresca sondeando y no por WebSocket a proposito. El socket
 *   se cae cuando el carro se reinicia, que es justo el momento que
 *   interesa observar; el sondeo se recupera solo en el siguiente
 *   intento y muestra el reinicio en vez de quedarse mudo.
 *   ============================================================ */

#pragma once

static const char PAGINA_DIAG[] = R"HTMLDIAG(
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Diagnostico — Smart Car 03</title>
<style>
  :root{
    --fondo:#05070f; --panel:#0b1224; --borde:#1e2c4a;
    --cian:#00e5ff; --azul:#2979ff; --ok:#00e676; --alerta:#ff3d5a; --ambar:#ffb300;
    --texto:#e6f3ff; --tenue:#7a8ca8;
  }
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;background:var(--fondo);color:var(--texto);
    font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:12px}
  h1{font-size:14px;letter-spacing:2px;margin:0 0 12px;color:var(--cian);font-weight:700}
  h1 a{float:right;font-size:11px;color:var(--tenue);text-decoration:none;letter-spacing:1px}

  .panel{background:var(--panel);border:1px solid var(--borde);border-radius:12px;
    padding:12px;margin-bottom:12px}
  .rotulo{font-size:10px;letter-spacing:1.5px;color:var(--tenue);font-weight:700;
    margin-bottom:8px}

  .rejilla{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px}
  .dato{display:flex;flex-direction:column;gap:3px}
  .dato .k{font-size:9px;letter-spacing:1px;color:var(--tenue);font-weight:700}
  .dato .v{font-size:15px;font-weight:600;font-variant-numeric:tabular-nums}

  .destacado{grid-column:1/-1;padding:10px;border-radius:8px;
    background:rgba(0,230,118,.08);border:1px solid rgba(0,230,118,.35)}
  .destacado.malo{background:rgba(255,61,90,.10);border-color:rgba(255,61,90,.5)}
  .destacado.malo .v{color:var(--alerta)}

  .botones{display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:8px}
  button{padding:12px 8px;border-radius:10px;background:rgba(0,229,255,.12);
    border:1px solid rgba(0,229,255,.5);color:var(--cian);font-size:11px;
    letter-spacing:1.5px;font-weight:700;cursor:pointer}
  button:active{background:rgba(0,229,255,.28)}
  button:disabled{opacity:.35}
  button.peligro{background:rgba(255,179,0,.12);border-color:rgba(255,179,0,.5);
    color:var(--ambar)}

  .aviso{font-size:10px;color:var(--tenue);margin-top:8px;line-height:1.5}

  pre{margin:0;background:#03060c;border:1px solid var(--borde);border-radius:8px;
    padding:10px;font-size:10px;line-height:1.45;height:46vh;overflow:auto;
    white-space:pre-wrap;word-break:break-word;
    font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color:#bcd4e8}

  .ajuste{display:flex;align-items:center;justify-content:space-between;gap:10px;
    padding:7px 0;border-bottom:1px solid rgba(30,44,74,.6)}
  .ajuste:last-child{border-bottom:none}
  .ajuste .k{font-size:10px;letter-spacing:1px;color:var(--tenue);font-weight:700}
  .mando{display:flex;align-items:center;gap:10px}
  .mando .v{font-size:14px;font-weight:700;min-width:58px;text-align:center;
    font-variant-numeric:tabular-nums}

  .fila{display:flex;align-items:center;gap:8px;margin-bottom:8px}
  .fila .rotulo{margin:0;flex:1}
  .mini{padding:5px 10px;font-size:10px;letter-spacing:1px}
  #enlace{width:8px;height:8px;border-radius:50%;background:var(--tenue)}
  #enlace.vivo{background:var(--ok)}
</style>
</head>
<body>

<h1>DIAGNOSTICO <a href="/">volver al mando &rsaquo;</a></h1>

<div class="panel">
  <div class="fila"><div class="rotulo">ESTADO</div><div id="enlace"></div></div>
  <div class="rejilla">
    <div class="dato destacado" id="cajaReinicio">
      <div class="k">ULTIMO REINICIO</div>
      <div class="v" id="reinicio">...</div>
    </div>
    <div class="dato"><div class="k">ENCENDIDO HACE</div><div class="v" id="uptime">...</div></div>
    <div class="dato"><div class="k">MEMORIA LIBRE</div><div class="v" id="heap">...</div></div>
    <div class="dato"><div class="k">MINIMO HISTORICO</div><div class="v" id="heapMin">...</div></div>
    <div class="dato"><div class="k">RUEDA IZQ</div><div class="v" id="izq">...</div></div>
    <div class="dato"><div class="k">RUEDA DER</div><div class="v" id="der">...</div></div>
    <div class="dato"><div class="k">SONAR</div><div class="v" id="sonar">...</div></div>
  </div>
</div>

<div class="panel">
  <div class="rotulo">REPETIR UNA PRUEBA</div>
  <div class="botones">
    <button onclick="probar('servo')">SERVO</button>
    <button onclick="probar('sonar')">SONAR</button>
    <button class="peligro" onclick="probar('izq')">MOTOR IZQ</button>
    <button class="peligro" onclick="probar('der')">MOTOR DER</button>
    <button class="peligro" onclick="probar('todo')">TODO</button>
  </div>
  <div class="aviso">
    Las pruebas de motor mueven las ruedas. Levanta el carro antes de tocarlas.
    El resultado aparece abajo en unos segundos.
  </div>
</div>

<div class="panel">
  <div class="fila">
    <div class="rotulo">CALIBRACION</div>
    <button class="mini" onclick="restaurar()">DE FABRICA</button>
  </div>
  <div id="ajustes"></div>
  <div class="aviso">
    Cada cambio se guarda solo en el carro y sobrevive al apagado.
  </div>
</div>

<div class="panel">
  <div class="fila">
    <div class="rotulo">CONSOLA</div>
    <button class="mini" onclick="seguir=!seguir;pintarSeguir()" id="btnSeguir">SEGUIR</button>
  </div>
  <pre id="log">conectando...</pre>
</div>

<script>
let seguir = true;

function pintarSeguir(){
  document.getElementById('btnSeguir').style.opacity = seguir ? 1 : .35;
}

function duracion(s){
  const h = Math.floor(s/3600), m = Math.floor(s%3600/60);
  if (h) return h + 'h ' + m + 'm';
  if (m) return m + 'm ' + (s%60) + 's';
  return s + 's';
}

async function pedirEstado(){
  try {
    const e = await (await fetch('/estado')).json();
    document.getElementById('enlace').className = 'vivo';

    document.getElementById('reinicio').textContent = e.reinicio;
    // Cualquier reinicio que no sea el de encender la placa merece mirarse: el
    // carro se reinicio solo mientras estaba en uso.
    const sano = e.reinicio.indexOf('encendido normal') === 0;
    document.getElementById('cajaReinicio').className =
      'dato destacado' + (sano ? '' : ' malo');

    document.getElementById('uptime').textContent = duracion(e.uptime);
    document.getElementById('heap').textContent = (e.heap/1024).toFixed(1) + ' KB';
    document.getElementById('heapMin').textContent = (e.heap_min/1024).toFixed(1) + ' KB';
    document.getElementById('izq').textContent = e.izq + (e.failsafe ? ' (failsafe)' : '');
    document.getElementById('der').textContent = e.der;
    document.getElementById('sonar').textContent =
      e.distancia > 0 ? (e.angulo + '° → ' + e.distancia.toFixed(0) + ' cm')
                      : (e.angulo + '° → sin eco');
  } catch (err) {
    document.getElementById('enlace').className = '';
  }
}

async function pedirLog(){
  try {
    const t = await (await fetch('/log')).text();
    const caja = document.getElementById('log');
    // Solo se reescribe si cambio: repintar un bloque largo cada segundo hace
    // saltar el scroll y pierde la seleccion de texto.
    if (caja.textContent !== t) {
      caja.textContent = t;
      if (seguir) caja.scrollTop = caja.scrollHeight;
    }
  } catch (err) {}
}

function probar(cual){
  fetch('/probar?q=' + cual);
}

// Rotulo y paso de cada ajuste. El paso importa: mover un trim de a 1 es
// perder la tarde, y mover el tiempo de giro de a 1 ms no cambia nada.
const AJUSTES = [
  ['trim_izquierda', 'TRIM RUEDA IZQ',    '%',  5],
  ['trim_derecha',   'TRIM RUEDA DER',    '%',  5],
  ['pwm_min',        'PWM MINIMO',        '',   5],
  ['invertir_izq',   'INVERTIR IZQ',      '',   1],
  ['invertir_der',   'INVERTIR DER',      '',   1],
  ['angulo_min',     'SERVO: TOPE IZQ',   '°', 5],
  ['angulo_max',     'SERVO: TOPE DER',   '°', 5],
  ['invertir_servo', 'INVERTIR RADAR',    '',   1],
  ['pwm_giro',       'FUERZA DEL GIRO',   '',  10],
  ['giro_ms',        'DURACION DEL GIRO', 'ms', 50]
];

function pintarAjustes(v){
  const caja = document.getElementById('ajustes');
  caja.innerHTML = '';

  for (const [clave, rotulo, unidad, paso] of AJUSTES) {
    const fila = document.createElement('div');
    fila.className = 'ajuste';

    // Los booleanos se muestran como un solo boton que alterna, no como un par
    // de flechas: sumarle 1 a algo que solo vale 0 o 1 no significa nada.
    const esBooleano = (paso === 1 && clave.indexOf('invertir') === 0);

    fila.innerHTML =
      '<div class="k">' + rotulo + '</div>' +
      (esBooleano
        ? '<button class="mini" onclick="fijar(\'' + clave + '\',' + (v[clave] ? 0 : 1) + ')">' +
            (v[clave] ? 'SI' : 'NO') + '</button>'
        : '<div class="mando">' +
            '<button class="mini" onclick="fijar(\'' + clave + '\',' + (v[clave] - paso) + ')">−</button>' +
            '<span class="v">' + v[clave] + unidad + '</span>' +
            '<button class="mini" onclick="fijar(\'' + clave + '\',' + (v[clave] + paso) + ')">+</button>' +
          '</div>');

    caja.appendChild(fila);
  }
}

async function fijar(clave, valor){
  // El carro responde con el estado completo, asi que la pantalla se repinta
  // con lo que quedo de verdad y no con lo que se pidio. Si el valor se recorto
  // por estar fuera de rango, se ve al instante.
  try { pintarAjustes(await (await fetch('/ajustar?k=' + clave + '&v=' + valor)).json()); }
  catch (err) {}
}

async function restaurar(){
  try { pintarAjustes(await (await fetch('/ajustar?k=reset')).json()); }
  catch (err) {}
}

async function pedirAjustes(){
  try { pintarAjustes(await (await fetch('/ajustes')).json()); }
  catch (err) {}
}

pintarSeguir();
pedirAjustes();
pedirEstado();
pedirLog();
setInterval(pedirEstado, 1000);
setInterval(pedirLog, 1500);
</script>

</body>
</html>
)HTMLDIAG";
