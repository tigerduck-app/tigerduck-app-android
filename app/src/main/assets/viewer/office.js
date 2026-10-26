// Fetches the document the app serves at /document and renders it: a Word document with
// docx-preview, a PowerPoint deck one slide after another with pptx-to-html. The page's title
// tells the app how it went, and why it failed, so the page needs no bridge to it. PowerPoint's renderer loads
// only for a deck, so a Word document never waits on it or fails with it.

const kind = new URLSearchParams(location.search).get("kind");
const root = document.getElementById("document");

async function show() {
  const response = await fetch("/document");
  if (!response.ok) throw new Error("document");
  const data = await response.arrayBuffer();
  if (kind === "docx") {
    await docx.renderAsync(data, root, null, { inWrapper: true, useBase64URL: true, ignoreLastRenderedPageBreak: true });
  } else if (kind === "pptx") {
    const { pptxToHtml } = await import("./pptx-to-html.js");
    // Each slide at the deck's own size and shape.
    const slides = await pptxToHtml(data);
    if (slides.length === 0) throw new Error("empty");
    for (const html of slides) {
      const slide = document.createElement("section");
      slide.className = "deck-slide";
      slide.innerHTML = html;
      root.appendChild(slide);
    }
  } else {
    throw new Error("kind");
  }
  document.title = "shown";
}

show().catch((error) => {
  const why = error instanceof Error ? error.name + ": " + error.message : String(error);
  document.title = ("failed: " + why).slice(0, 300);
});
