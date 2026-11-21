// The browser's empty-window script (app/.../browser/BrowserPageScripts.kt, EMPTY_WINDOW), run in
// jsdom: a window.open() with no address or about:blank answers null and opens nothing, as on the
// iPhone, and every other address goes on to the WebView's own open untouched. The script is the one
// Kotlin generates: PageScriptsExport, in :app's unit tests, writes it to app/build/page-scripts.
// Run: npm ci --prefix tools/page-scripts, then
// node tools/page-scripts/empty-window.test.cjs app/build/page-scripts
const path = require('path');
const fs = require('fs');
const { JSDOM } = require('jsdom');
const [scriptsDir] = process.argv.slice(2);
const script = fs.readFileSync(path.join(scriptsDir, 'empty-window.js'), 'utf8');

let failures = 0, passes = 0;
function expect(label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) { passes++; console.log('pass', label); } else { failures++; console.log('FAIL', label, 'got', a, 'expected', e); }
}

/**
 * A page with the WebView's open stood in for: as the real one, it reads its address as a string,
 * and with one window it opens the address in this one and answers it.
 */
function page(body = '') {
  const dom = new JSDOM(`<!doctype html><body>${body}</body>`, { url: 'https://moodle2.ntust.edu.tw/course/view.php?id=1', runScripts: 'outside-only' });
  const w = dom.window;
  w.opened = [];
  w.eval('window.open = function (url) { opened.push([String(url)].concat(Array.prototype.slice.call(arguments, 1))); return window; };');
  w.eval(script);
  return w;
}

for (const call of ['open()', "open('')", 'open(undefined)', "open('about:blank')", "open('ABOUT:BLANK')", "open('about:Blank')", "open(' about:blank ')",
  "open('about:blank', 'report', 'width=600')", "open(new URL('about:blank'))", "window.open('about:blank', '_blank')"]) {
  const w = page();
  expect(`${call}: null, and nothing opened`, [w.eval(call) === null, w.opened], [true, []]);
}

for (const [call, args] of [
  ["open('https://moodle2.ntust.edu.tw/mod/forum/view.php?id=2')", ['https://moodle2.ntust.edu.tw/mod/forum/view.php?id=2']],
  ["window.open('/mod/resource/view.php?id=3', '_blank', 'noopener')", ['/mod/resource/view.php?id=3', '_blank', 'noopener']],
  ["open('mailto:someone@example.com')", ['mailto:someone@example.com']],
  ["open('about:blank#top')", ['about:blank#top']],
  ['open(null)', ['null']],
  ["(function () { var n = 0; return open({ toString: function () { return ++n === 1 ? '/report' : 'about:blank'; } }); })()", ['/report']],
]) {
  const w = page();
  expect(`${call}: goes on to the WebView's open, as asked`, [w.eval(call) === w, w.opened], [true, [args]]);
}

{
  const w = page('<img name="baseURI" alt=""><base href="https://moodle2.ntust.edu.tw/mod/">');
  expect('an element named baseURI changes nothing', [w.eval("open('about:blank') === null"), w.eval("open('forum/') === window"), w.opened], [true, true, [['forum/']]]);
}

{
  const w = page();
  w.eval("var detached = window.open; detached('about:blank'); detached('https://moodle2.ntust.edu.tw/my/');");
  expect('called detached from window: the same rule', w.opened, [['https://moodle2.ntust.edu.tw/my/']]);
}

console.log(`${passes} passed, ${failures} failed`);
process.exit(failures ? 1 : 0);
