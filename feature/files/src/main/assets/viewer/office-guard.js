// Loaded first, as a plain script: says the page failed when one of its scripts can't load or
// throws before office.js can say how it went (a WebView too old for import maps, say). The title
// carries why ("failed: …"), for the app to print under "can't be shown here".
function failed(why) {
  if (document.title !== "shown" && !document.title.startsWith("failed")) {
    document.title = ("failed: " + why).slice(0, 300);
  }
}
window.addEventListener("error", (event) => failed(event.message || "script error"));
window.addEventListener("unhandledrejection", (event) => {
  const reason = event.reason;
  failed(reason instanceof Error ? reason.name + ": " + reason.message : String(reason));
});
document.addEventListener("error", (event) => {
  if (event.target instanceof HTMLScriptElement) {
    failed("script didn't load: " + (event.target.src || "").split("/").pop());
  }
}, true);
