import JSZip from "jszip";
//#region src/core/XmlHelper.ts
function libWarn(msg) {
	if (typeof console !== "undefined" && console.warn) console.warn(`[pptx-to-html] ${msg}`);
}
var XmlHelper = class XmlHelper {
	static domParserFactory = null;
	/**
	* Parses a string containing XML into a DOM Document
	* @param xmlString XML string to parse
	* @returns DOM Document
	*/
	static parseXml(xmlString) {
		if (XmlHelper.domParserFactory) return XmlHelper.domParserFactory().parseFromString(xmlString, "application/xml");
		const DP = globalThis?.DOMParser;
		if (typeof DP === "function") return new DP().parseFromString(xmlString, "application/xml");
		libWarn("No DOMParser available. Provide one through XmlHelper.setDomParser() or pptxToHtml({ domParserFactory }).");
		throw new Error("DOMParser not available in this environment");
	}
	/**
	* Gets a direct child element by local tag name
	*/
	static getDirectChildrenByTagName(parent, tag) {
		return Array.from(parent.children).filter((child) => child.localName === tag);
	}
	/**
	* Gets attribute value as number, defaulting to 0
	*/
	static getAttrAsNumber(el, name) {
		const raw = el.getAttribute(name);
		if (raw == null || raw === "") return 0;
		const n = Number(raw);
		return Number.isFinite(n) ? n : 0;
	}
	/** Returns the paint order of an element's top-level ancestor in a shape tree. */
	static getZIndex(element, spTree) {
		let top = element;
		while (top?.parentElement && top.parentElement !== spTree) top = top.parentElement;
		if (!top || top.parentElement !== spTree) return 0;
		return Array.from(spTree.children).indexOf(top);
	}
	/**
	* Resolves a drawing object's transform into slide coordinates. Child objects
	* inside a `p:grpSp` are expressed in the group's child coordinate system,
	* so using their local `a:xfrm` directly places them incorrectly.
	*/
	static getAbsoluteTransform(element, spTree) {
		const readTransform = (node) => {
			const xfrm = node.getElementsByTagNameNS("*", "xfrm")[0] ?? null;
			const off = xfrm?.getElementsByTagNameNS("*", "off")[0] ?? null;
			const ext = xfrm?.getElementsByTagNameNS("*", "ext")[0] ?? null;
			const chOff = xfrm?.getElementsByTagNameNS("*", "chOff")[0] ?? null;
			const chExt = xfrm?.getElementsByTagNameNS("*", "chExt")[0] ?? null;
			return {
				xfrm,
				x: off ? XmlHelper.getAttrAsNumber(off, "x") : 0,
				y: off ? XmlHelper.getAttrAsNumber(off, "y") : 0,
				cx: ext ? XmlHelper.getAttrAsNumber(ext, "cx") : 1e6,
				cy: ext ? XmlHelper.getAttrAsNumber(ext, "cy") : 5e5,
				chX: chOff ? XmlHelper.getAttrAsNumber(chOff, "x") : 0,
				chY: chOff ? XmlHelper.getAttrAsNumber(chOff, "y") : 0,
				chCx: chExt ? XmlHelper.getAttrAsNumber(chExt, "cx") : 0,
				chCy: chExt ? XmlHelper.getAttrAsNumber(chExt, "cy") : 0,
				rotationDeg: xfrm?.getAttribute("rot") ? Number(xfrm.getAttribute("rot")) / 6e4 : 0
			};
		};
		const own = readTransform(element);
		let { x, y, cx, cy } = own;
		let rotationDeg = own.rotationDeg;
		let parent = element.parentElement;
		while (parent && parent !== spTree) {
			if (parent.localName === "grpSp") {
				const group = readTransform(parent);
				const sx = group.chCx > 0 ? group.cx / group.chCx : 1;
				const sy = group.chCy > 0 ? group.cy / group.chCy : 1;
				x = group.x + (x - group.chX) * sx;
				y = group.y + (y - group.chY) * sy;
				cx *= sx;
				cy *= sy;
				rotationDeg += group.rotationDeg;
			}
			parent = parent.parentElement;
		}
		return {
			x,
			y,
			cx,
			cy,
			rotationDeg: rotationDeg || void 0
		};
	}
	static getColorFromElement(el, themeColors) {
		if (!el) return void 0;
		const srgb = el.getElementsByTagNameNS("*", "srgbClr")[0];
		if (srgb) {
			const val = srgb.getAttribute("val");
			return val ? `#${val}` : void 0;
		}
		const scheme = el.getElementsByTagNameNS("*", "schemeClr")[0];
		if (scheme) {
			const val = scheme.getAttribute("val");
			if (val && themeColors) return themeColors[{
				bg1: "lt1",
				bg2: "lt2",
				tx1: "dk1",
				tx2: "dk2"
			}[val] || val];
			return;
		}
		const sys = el.getElementsByTagNameNS("*", "sysClr")[0];
		if (sys) {
			const lastClr = sys.getAttribute("lastClr");
			return lastClr ? `#${lastClr}` : void 0;
		}
	}
	static extractThemeColors(themeDoc) {
		if (!themeDoc) return {};
		const NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
		const themeColors = {};
		const clrScheme = themeDoc.getElementsByTagNameNS(NS, "clrScheme")[0];
		if (!clrScheme) return {};
		for (const node of Array.from(clrScheme.children)) {
			const name = node.localName;
			const srgbClr = node.getElementsByTagNameNS(NS, "srgbClr")[0];
			const sysClr = node.getElementsByTagNameNS(NS, "sysClr")[0];
			const hex = srgbClr?.getAttribute("val") ?? sysClr?.getAttribute("lastClr");
			if (hex) themeColors[name] = `#${hex}`;
		}
		return themeColors;
	}
	/**
	* Extracts table styles (fills and text colors per region) from theme XML.
	* Returns a map keyed by styleId (GUID or name), with region color maps.
	*/
	static extractThemeTableStyles(themeDoc) {
		const styles = {};
		if (!themeDoc) return styles;
		const themeColors = XmlHelper.extractThemeColors(themeDoc);
		const tblStyleLst = themeDoc.getElementsByTagNameNS("*", "tblStyleLst")[0] || null;
		if (!tblStyleLst) return styles;
		const tblStyles = Array.from(tblStyleLst.getElementsByTagNameNS("*", "tblStyle"));
		for (const ts of tblStyles) {
			const id = ts.getAttribute("styleId") || ts.getAttribute("name") || "";
			if (!id) continue;
			const fills = {};
			const fontColors = {};
			const prNodes = Array.from(ts.getElementsByTagNameNS("*", "tblStylePr"));
			for (const pr of prNodes) {
				const type = pr.getAttribute("type") || pr.getAttribute("val") || "";
				if (!type) continue;
				const tcStyle = pr.getElementsByTagNameNS("*", "tcStyle")[0] || null;
				const solidCandidates = [
					(tcStyle?.getElementsByTagNameNS("*", "tcPr")[0] || null)?.getElementsByTagNameNS("*", "solidFill")[0] || null,
					tcStyle?.getElementsByTagNameNS("*", "solidFill")[0] || null,
					pr.getElementsByTagNameNS("*", "solidFill")[0] || null
				];
				let fillColor;
				for (const cand of solidCandidates) if (cand && !fillColor) fillColor = XmlHelper.getColorFromElement(cand, themeColors);
				if (!fillColor) {
					const fillRef = tcStyle?.getElementsByTagNameNS("*", "fillRef")[0] || pr.getElementsByTagNameNS("*", "fillRef")[0] || null;
					fillColor = XmlHelper.getColorFromElement(fillRef, themeColors);
				}
				if (fillColor) fills[type] = fillColor;
				const txStyle = pr.getElementsByTagNameNS("*", "tcTxStyle")[0] || null;
				const txFillSolid = txStyle?.getElementsByTagNameNS("*", "solidFill")[0] || null;
				let textColor = XmlHelper.getColorFromElement(txFillSolid, themeColors);
				if (!textColor) {
					const fontRef = txStyle?.getElementsByTagNameNS("*", "fontRef")[0] || null;
					textColor = XmlHelper.getColorFromElement(fontRef, themeColors);
				}
				if (!textColor) {
					const anyScheme = txStyle?.getElementsByTagNameNS("*", "schemeClr")[0] || null;
					textColor = XmlHelper.getColorFromElement(anyScheme, themeColors);
				}
				if (textColor) fontColors[type] = textColor;
			}
			styles[id] = {
				fills,
				fontColors
			};
		}
		return styles;
	}
	/** Allow a host application to provide a DOM parser instance factory. */
	static setDomParser(factory) {
		XmlHelper.domParserFactory = factory;
	}
	/** Relationship lookup: by Type suffix (avoids querySelector CSS) */
	static findRelationshipByTypeSuffix(doc, suffix) {
		const rels = doc.getElementsByTagName("Relationship");
		for (const el of Array.from(rels)) if ((el.getAttribute("Type") || "").endsWith(suffix)) return el;
		return null;
	}
	/** Relationship lookup: by Id */
	static findRelationshipById(doc, id) {
		const rels = doc.getElementsByTagName("Relationship");
		for (const el of Array.from(rels)) if (el.getAttribute("Id") === id) return el;
		return null;
	}
};
//#endregion
//#region src/elements/TextExtractor.ts
/**
* Responsible for extracting text elements from a slide XML node.
*/
var TextExtractor = class {
	/**
	* Extracts all text elements from the slide's <spTree> node.
	* @param spTree The <spTree> element of the slide.
	* @param themeColors Theme color mapping (e.g. tx1, bg2).
	* @returns List of TextElement extracted.
	*/
	static extract(spTree, themeColors, opts = {}) {
		if (!spTree) return [];
		const elements = [];
		const shapes = spTree.getElementsByTagNameNS("*", "sp");
		for (const shape of Array.from(shapes)) {
			const ph = (shape.getElementsByTagNameNS("*", "nvPr")[0] ?? null)?.getElementsByTagNameNS("*", "ph")[0] ?? null;
			const isPlaceholder = !!ph;
			if (opts.context && opts.context !== "slide" && isPlaceholder) continue;
			const txBody = shape.getElementsByTagNameNS("*", "txBody")[0];
			if (!txBody) continue;
			const paragraphs = txBody.getElementsByTagNameNS("*", "p");
			const bodyPr = txBody.getElementsByTagNameNS("*", "bodyPr")[0] ?? null;
			const anchor = bodyPr?.getAttribute("anchor") || void 0;
			const phIdx = ph?.getAttribute("idx") || void 0;
			const phType = ph?.getAttribute("type") || (phIdx ? opts.placeholderTypes?.[phIdx] : void 0) || void 0;
			const isTitlePh = phType === "title" || phType === "ctrTitle";
			const verticalAlign = anchor === "ctr" ? "middle" : anchor === "b" ? "bottom" : isTitlePh ? "bottom" : "top";
			const lIns = bodyPr?.getAttribute("lIns");
			const tIns = bodyPr?.getAttribute("tIns");
			const rIns = bodyPr?.getAttribute("rIns");
			const bIns = bodyPr?.getAttribute("bIns");
			const padding = {
				left: lIns ? Number(lIns) / 9525 : 0,
				top: tIns ? Number(tIns) / 9525 : 0,
				right: rIns ? Number(rIns) / 9525 : 0,
				bottom: bIns ? Number(bIns) / 9525 : 0
			};
			const textRuns = [];
			let fontName = "Arial";
			let fontSize = isTitlePh ? 44 : phType === "subTitle" ? 24 : 18;
			let fontWeight = isTitlePh ? "bold" : "normal";
			const isCenteredPh = isTitlePh || phType === "subTitle";
			let color = void 0;
			let horizontalAlign = void 0;
			const paraItems = [];
			const lvlDefaults = {};
			const lstStyle = txBody.querySelector("*|lstStyle");
			if (lstStyle) for (const node of Array.from(lstStyle.children)) {
				const m = node.localName.match(/^lvl(\d+)pPr$/);
				if (!m) continue;
				const idx = parseInt(m[1], 10) - 1;
				let kind = "p";
				let listStyle = void 0;
				if (node.querySelector("*|buNone")) kind = "p";
				else if (node.querySelector("*|buAutoNum")) {
					kind = "ol";
					listStyle = mapAutoNumToCss(node.querySelector("*|buAutoNum")?.getAttribute("type") || "arabicPeriod");
				} else if (node.querySelector("*|buChar")) {
					kind = "ul";
					listStyle = "disc";
				}
				lvlDefaults[idx] = {
					kind,
					listStyle
				};
			}
			for (const p of Array.from(paragraphs)) {
				const pPr = p.getElementsByTagNameNS("*", "pPr")[0] ?? null;
				const algn = pPr?.getAttribute("algn") || void 0;
				if (algn && !horizontalAlign) horizontalAlign = algn === "ctr" ? "center" : algn === "r" ? "right" : algn.startsWith("just") ? "justify" : "left";
				const runs = p.getElementsByTagNameNS("*", "r");
				let paraText = getParagraphText(p);
				if (paraText) paraText.split(/\n+/).forEach((t) => {
					if (t) textRuns.push(t);
				});
				for (const r of Array.from(runs)) {
					const rPr = r.getElementsByTagNameNS("*", "rPr")[0];
					if (rPr) {
						fontName = rPr.getElementsByTagNameNS("*", "latin")[0]?.getAttribute("typeface") ?? fontName;
						const sz = rPr.getAttribute("sz");
						if (sz) {
							const n = parseInt(sz, 10);
							if (Number.isFinite(n)) fontSize = n / 100;
						}
						const bAttr = rPr.getAttribute("b");
						if (bAttr === "1") fontWeight = "bold";
						else if (bAttr === "0") fontWeight = "normal";
						const solidFill = rPr.querySelector("*|solidFill");
						const candidate = XmlHelper.getColorFromElement(solidFill || null, themeColors);
						if (candidate) color = candidate;
					}
				}
				if (!color && pPr) {
					const endFill = (pPr.getElementsByTagNameNS("*", "endParaRPr")[0] ?? null)?.querySelector("*|solidFill") || null;
					const c1 = XmlHelper.getColorFromElement(endFill, themeColors);
					if (c1) color = c1;
					if (!color) {
						const defFillP = (pPr.getElementsByTagNameNS("*", "defRPr")[0] ?? null)?.querySelector("*|solidFill") || null;
						const c2 = XmlHelper.getColorFromElement(defFillP, themeColors);
						if (c2) color = c2;
					}
				}
				const lvlAttr = pPr?.getAttribute("lvl");
				const lvl = lvlAttr ? parseInt(lvlAttr, 10) : 0;
				let kind = "p";
				let listStyle = void 0;
				if (pPr) {
					if (pPr.querySelector("*|buNone")) kind = "p";
					else if (pPr.querySelector("*|buAutoNum")) {
						kind = "ol";
						listStyle = mapAutoNumToCss(pPr.querySelector("*|buAutoNum")?.getAttribute("type") || "arabicPeriod");
					} else if (pPr.querySelector("*|buChar")) {
						kind = "ul";
						listStyle = "disc";
					} else if (lvlDefaults[lvl]) {
						kind = lvlDefaults[lvl].kind;
						listStyle = lvlDefaults[lvl].listStyle;
					} else if (lvl > 0) {
						kind = "ul";
						listStyle = "disc";
					}
				} else if (lvlDefaults[lvl]) {
					kind = lvlDefaults[lvl].kind;
					listStyle = lvlDefaults[lvl].listStyle;
				} else if (lvl > 0) {
					kind = "ul";
					listStyle = "disc";
				}
				paraItems.push({
					kind,
					text: paraText,
					lvl: isNaN(lvl) ? 0 : lvl,
					listStyle
				});
			}
			if (!color) {
				const lstFill = txBody.querySelector("*|lstStyle *|defRPr")?.querySelector("*|solidFill");
				const c0 = XmlHelper.getColorFromElement(lstFill || null, themeColors);
				if (c0) color = c0;
				const defFill = txBody.querySelector("*|defRPr")?.querySelector("*|solidFill");
				const fallback1 = XmlHelper.getColorFromElement(defFill || null, themeColors);
				if (fallback1) color = fallback1;
				if (!color) {
					const shapeFill = shape.querySelector("p\\:spPr, spPr")?.querySelector("*|solidFill");
					const fallback2 = XmlHelper.getColorFromElement(shapeFill || null, themeColors);
					if (fallback2) color = fallback2;
				}
			}
			const content = textRuns.join(" ").trim();
			if (opts.context && opts.context !== "slide") {
				const c = content.toLowerCase();
				if (c.includes("click to add") || c.includes("click to edit") || c.includes("haga clic para agregar") || c.includes("haga clic para editar") || c.includes("hacer clic para agregar") || c.includes("hacer clic para editar")) continue;
			}
			if (content === "") continue;
			const xfrm = shape.getElementsByTagNameNS("*", "xfrm")[0];
			const off = xfrm?.getElementsByTagNameNS("*", "off")[0] ?? null;
			const ext = xfrm?.getElementsByTagNameNS("*", "ext")[0] ?? null;
			let x, y, cx, cy, rotationDeg;
			if (off && ext) ({x, y, cx, cy, rotationDeg} = XmlHelper.getAbsoluteTransform(shape, spTree));
			else if (opts.placeholderGeom) {
				const g = (phIdx ? opts.placeholderGeom[phIdx] : void 0) ?? (phType ? opts.placeholderGeom["type:" + phType] : void 0);
				x = g?.x ?? 0;
				y = g?.y ?? 0;
				cx = g?.cx ?? 1e6;
				cy = g?.cy ?? 5e5;
			} else {
				x = 0;
				y = 0;
				cx = 1e6;
				cy = 5e5;
			}
			if (phType === "body" && paraItems.length > 1) {
				for (const it of paraItems) if (it.kind === "p") {
					it.kind = "ul";
					it.listStyle = "disc";
				}
			}
			let richHtml = void 0;
			if (paraItems.length > 1 || paraItems.some((it) => it.kind !== "p")) {
				const parts = [];
				let open = null;
				for (const it of paraItems) {
					if (it.kind === "p") {
						if (open) {
							parts.push(open.kind === "ul" ? "</ul>" : "</ol>");
							open = null;
						}
						if (it.text.trim()) parts.push(`<div style="margin-left:${it.lvl * 24}px">${escapeHtml(it.text).replace(/\n/g, "<br>")}</div>`);
						continue;
					}
					if (!open || open.kind !== it.kind) {
						if (open) parts.push(open.kind === "ul" ? "</ul>" : "</ol>");
						const commonListCss = `list-style-position: inside; padding-left: 0; margin: 0;`;
						const style = it.kind === "ol" ? ` style="${commonListCss} list-style-type: ${it.listStyle || "decimal"};"` : ` style="${commonListCss}"`;
						parts.push(it.kind === "ul" ? `<ul${style}>` : `<ol${style}>`);
						open = {
							kind: it.kind,
							listStyle: it.listStyle
						};
					}
					parts.push(`<li style="margin-left:${it.lvl * 24}px">${escapeHtml(it.text).replace(/\n/g, "<br>")}</li>`);
				}
				if (open) parts.push(open.kind === "ul" ? "</ul>" : "</ol>");
				richHtml = parts.join("");
			}
			const element = {
				type: "text",
				zIndex: XmlHelper.getZIndex(shape, spTree),
				rotationDeg,
				content,
				position: {
					x,
					y
				},
				size: {
					width: cx,
					height: cy
				},
				font: {
					name: fontName,
					size: fontSize,
					color: color ?? "#000000",
					weight: fontWeight
				},
				align: {
					horizontal: horizontalAlign ?? (isCenteredPh ? "center" : "left"),
					vertical: verticalAlign
				},
				padding,
				html: richHtml
			};
			elements.push(element);
		}
		return elements;
	}
};
function getParagraphText(p) {
	let out = "";
	for (const child of Array.from(p.childNodes)) {
		if (child.nodeType !== 1) continue;
		const ln = child.localName;
		if (ln === "r") {
			const t = child.getElementsByTagNameNS("*", "t")[0]?.textContent ?? "";
			out += t;
		} else if (ln === "br") out += "\n";
		else if (ln === "fld") {
			const runs = child.getElementsByTagNameNS("*", "r");
			for (const r of Array.from(runs)) {
				const t = r.getElementsByTagNameNS("*", "t")[0]?.textContent ?? "";
				out += t;
			}
		} else if (ln === "tab") out += "	";
	}
	return out;
}
function mapAutoNumToCss(typ) {
	const t = typ.toLowerCase();
	if (t.includes("alphauc")) return "upper-alpha";
	if (t.includes("alphalc")) return "lower-alpha";
	if (t.includes("romanu")) return "upper-roman";
	if (t.includes("romanl")) return "lower-roman";
	return "decimal";
}
function escapeHtml(str) {
	return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}
//#endregion
//#region src/renderer/htmlSafety.ts
/** Escapes content placed in an HTML attribute. */
function escapeAttribute(value) {
	return String(value ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}
/** PPTX colors are expected to be hex values. Reject arbitrary CSS from input XML. */
function safeColor(value, fallback = "transparent") {
	const color = String(value ?? "").trim();
	return /^#[0-9a-f]{3,8}$/i.test(color) || /^(transparent|currentcolor)$/i.test(color) ? color : fallback;
}
/** Keeps font names usable while preventing an XML value from breaking a style attribute. */
function safeFontFamily(value) {
	const font = String(value ?? "").trim();
	return /^[\p{L}\p{N} ,._-]{1,100}$/u.test(font) ? font : "Arial";
}
function imageMimeType(path) {
	switch (path.split(".").pop()?.toLowerCase()) {
		case "jpg":
		case "jpeg": return "image/jpeg";
		case "gif": return "image/gif";
		case "svg": return "image/svg+xml";
		case "webp": return "image/webp";
		case "bmp": return "image/bmp";
		case "tif":
		case "tiff": return "image/tiff";
		default: return "image/png";
	}
}
//#endregion
//#region src/elements/ImageExtractor.ts
/**
* Responsible for extracting image elements from a slide XML node.
*/
var ImageExtractor = class {
	/**
	* Extracts image elements from the <spTree> element using rels from slide relationships.
	* @param spTree The <spTree> element of the slide.
	* @param rels XML Document for slide relationships (ppt/slides/_rels/slideX.xml.rels).
	* @param zip The JSZip archive of the entire .pptx file.
	* @returns List of ImageElement extracted.
	*/
	static async extract(spTree, rels, zip, basePath = "ppt/slides") {
		if (!spTree) return [];
		const elements = [];
		const pics = spTree.getElementsByTagNameNS("*", "pic");
		for (const pic of Array.from(pics)) {
			const embedId = pic.getElementsByTagNameNS("*", "blip")[0]?.getAttribute("r:embed") ?? "";
			if (!embedId) continue;
			const relTarget = (rels && rels.getElementsByTagName ? (function() {
				const els = rels.getElementsByTagName("Relationship");
				for (const e of Array.from(els)) if (e.getAttribute("Id") === embedId) return e;
				return null;
			})() : null)?.getAttribute("Target");
			if (!relTarget) continue;
			const normalizedPath = this.normalizePath(relTarget, basePath);
			const imageFile = zip.file(normalizedPath);
			if (!imageFile) continue;
			const binary = await imageFile.async("base64");
			const dataUri = `data:${imageMimeType(normalizedPath)};base64,${binary}`;
			const { x, y, cx, cy, rotationDeg } = XmlHelper.getAbsoluteTransform(pic, spTree);
			const srcRect = pic.getElementsByTagNameNS("*", "srcRect")[0] ?? null;
			const crop = srcRect ? {
				left: Math.max(0, Number(srcRect.getAttribute("l") || 0)),
				top: Math.max(0, Number(srcRect.getAttribute("t") || 0)),
				right: Math.max(0, Number(srcRect.getAttribute("r") || 0)),
				bottom: Math.max(0, Number(srcRect.getAttribute("b") || 0))
			} : void 0;
			const element = {
				type: "image",
				zIndex: XmlHelper.getZIndex(pic, spTree),
				relId: embedId,
				src: dataUri,
				position: {
					x,
					y
				},
				size: {
					width: cx,
					height: cy
				},
				crop,
				rotationDeg
			};
			elements.push(element);
		}
		return elements;
	}
	/**
	* Normalizes a relative path from a slide rels file.
	* @param target Path from the relationship XML (e.g. "../media/image1.png")
	* @param basePath Base folder (e.g. "ppt/slides")
	* @returns Normalized path inside the zip (e.g. "ppt/media/image1.png")
	*/
	static normalizePath(target, basePath) {
		if (target.startsWith("/")) return target.slice(1);
		const parts = (basePath + "/" + target).split("/");
		const resolved = [];
		for (const part of parts) if (part === "..") resolved.pop();
		else if (part !== "." && part !== "") resolved.push(part);
		return resolved.join("/");
	}
};
//#endregion
//#region src/elements/ShapeExtractor.ts
/**
* Responsible for extracting shape elements (including connectors) from a slide XML node.
*/
var ShapeExtractor = class {
	/**
	* Extracts shape and connector elements from the <spTree> element of the slide.
	* @param spTree The <spTree> element.
	* @param themeColors Theme color mapping.
	* @returns List of ShapeElement extracted.
	*/
	static extract(spTree, themeColors) {
		if (!spTree) return [];
		const elements = [];
		const allShapes = [...Array.from(spTree.getElementsByTagNameNS("*", "sp")), ...Array.from(spTree.getElementsByTagNameNS("*", "cxnSp"))];
		for (const shape of allShapes) {
			const { x, y, cx, cy, rotationDeg } = XmlHelper.getAbsoluteTransform(shape, spTree);
			const shapeType = shape.getElementsByTagNameNS("*", "prstGeom")[0]?.getAttribute("prst") ?? "rect";
			const spPr = shape.getElementsByTagNameNS("*", "spPr")[0];
			let fillColor = "transparent";
			let borderColor = "transparent";
			let strokeWidth = void 0;
			let headEnd = void 0;
			let tailEnd = void 0;
			if (spPr) {
				const solidFill = spPr.getElementsByTagNameNS("*", "solidFill")[0] ?? null;
				fillColor = XmlHelper.getColorFromElement(solidFill, themeColors) ?? "transparent";
				const ln = spPr.getElementsByTagNameNS("*", "ln")[0];
				const borderFill = ln?.getElementsByTagNameNS("*", "solidFill")[0] ?? null;
				borderColor = XmlHelper.getColorFromElement(borderFill, themeColors) ?? "transparent";
				const wAttr = ln?.getAttribute("w");
				if (wAttr) {
					const w = Number(wAttr);
					if (!isNaN(w)) strokeWidth = w / 9525;
				}
				const headEndEl = ln?.getElementsByTagNameNS("*", "headEnd")[0] ?? null;
				const tailEndEl = ln?.getElementsByTagNameNS("*", "tailEnd")[0] ?? null;
				headEnd = headEndEl ? {
					type: headEndEl.getAttribute("type") || void 0,
					w: headEndEl.getAttribute("w") || void 0,
					len: headEndEl.getAttribute("len") || void 0
				} : void 0;
				tailEnd = tailEndEl ? {
					type: tailEndEl.getAttribute("type") || void 0,
					w: tailEndEl.getAttribute("w") || void 0,
					len: tailEndEl.getAttribute("len") || void 0
				} : void 0;
			}
			if (fillColor === "transparent") {
				const val = ((shape.getElementsByTagNameNS("*", "style")[0]?.getElementsByTagNameNS("*", "fillRef")[0])?.getElementsByTagNameNS("*", "schemeClr")[0])?.getAttribute("val");
				if (val && themeColors[val]) fillColor = themeColors[val];
			}
			const element = {
				type: "shape",
				zIndex: XmlHelper.getZIndex(shape, spTree),
				shapeType,
				position: {
					x,
					y
				},
				size: {
					width: cx,
					height: cy
				},
				fillColor,
				borderColor,
				strokeWidth,
				rotationDeg,
				headEnd,
				tailEnd
			};
			elements.push(element);
		}
		return elements;
	}
};
//#endregion
//#region src/elements/TableExtractor.ts
/**
* Extract tables from <spTree> by scanning p:graphicFrame with a:tbl content.
*/
var TableExtractor = class {
	static extract(spTree, themeColors, themeTableStyles) {
		if (!spTree) return [];
		const tables = [];
		const gFrames = spTree.getElementsByTagNameNS("*", "graphicFrame");
		for (const gf of Array.from(gFrames)) {
			const tbl = this.findTbl(gf);
			if (!tbl) continue;
			const { x, y, cx, cy, rotationDeg } = XmlHelper.getAbsoluteTransform(gf, spTree);
			const columns = [];
			const grid = tbl.getElementsByTagNameNS("*", "tblGrid")[0] ?? null;
			if (grid) for (const col of Array.from(grid.getElementsByTagNameNS("*", "gridCol"))) {
				const w = Number(col.getAttribute("w") || 0);
				columns.push(w);
			}
			const tblPr = tbl.getElementsByTagNameNS("*", "tblPr")[0] ?? null;
			const tableStyle = this.extractTableStyleFlags(tblPr);
			const tableBorders = this.extractTableBorders(tblPr, themeColors);
			const tableStyleId = this.extractTableStyleId(tblPr);
			const tableFillColor = this.extractTableFill(tblPr, themeColors);
			const resolvedStyle = tableStyleId && themeTableStyles ? themeTableStyles[tableStyleId] : void 0;
			const fallbackStyle = this.buildFallbackTableStyle(themeColors, tableStyle);
			const mergedStyle = fallbackStyle || resolvedStyle ? {
				fills: {
					...fallbackStyle?.fills || {},
					...resolvedStyle?.fills || {}
				},
				fontColors: {
					...fallbackStyle?.fontColors || {},
					...resolvedStyle?.fontColors || {}
				}
			} : void 0;
			const rows = [];
			for (const tr of Array.from(tbl.getElementsByTagNameNS("*", "tr"))) {
				const cells = [];
				for (const tc of Array.from(tr.getElementsByTagNameNS("*", "tc"))) {
					const tcPr = tc.getElementsByTagNameNS("*", "tcPr")[0] ?? null;
					if (tcPr?.getAttribute("hMerge") === "1" || tcPr?.getAttribute("hMerge") === "true" || tcPr?.getAttribute("vMerge") === "1" || tcPr?.getAttribute("vMerge") === "true" || !!tcPr?.getElementsByTagNameNS("*", "hMerge")[0] || !!tcPr?.getElementsByTagNameNS("*", "vMerge")[0]) continue;
					const txBody = tc.getElementsByTagNameNS("*", "txBody")[0] ?? null;
					const { text, font, align, padding } = this.extractCellText(txBody, themeColors, tc);
					const cell = {
						text,
						font,
						align,
						padding,
						fillColor: this.extractFillColor(tcPr, themeColors),
						borders: this.extractCellBorders(tcPr, themeColors)
					};
					const gridSpan = tcPr?.getElementsByTagNameNS("*", "gridSpan")[0] ?? null;
					const rowSpan = tcPr?.getElementsByTagNameNS("*", "rowSpan")[0] ?? null;
					if (gridSpan) cell.colSpan = Number(gridSpan.getAttribute("val") || 1);
					if (rowSpan) cell.rowSpan = Number(rowSpan.getAttribute("val") || 1);
					cells.push(cell);
				}
				rows.push({ cells });
			}
			tables.push({
				type: "table",
				zIndex: XmlHelper.getZIndex(gf, spTree),
				rotationDeg,
				position: {
					x,
					y
				},
				size: {
					width: cx,
					height: cy
				},
				columns,
				rows,
				tableStyle,
				tableBorders,
				tableStyleId,
				tableFillColor,
				style: mergedStyle ? {
					fills: mergedStyle.fills,
					fontColors: mergedStyle.fontColors
				} : void 0
			});
		}
		return tables;
	}
	static buildFallbackTableStyle(themeColors, tableStyleFlags) {
		const accent = themeColors["accent1"] || themeColors["accent2"] || themeColors["dk1"];
		if (!accent) return void 0;
		const white = themeColors["lt1"] || "#FFFFFF";
		const fills = {};
		const fontColors = {};
		if (tableStyleFlags?.firstRow) {
			fills["firstRow"] = accent;
			fontColors["firstRow"] = white;
		}
		if (tableStyleFlags?.firstCol) {
			fills["firstCol"] = this.lightenHex(accent, .85);
			fontColors["firstCol"] = void 0;
		}
		const bandShade = this.lightenHex(accent, .92);
		if (tableStyleFlags?.bandRow) fills["band2H"] = bandShade;
		if (tableStyleFlags?.bandCol) fills["band2V"] = bandShade;
		return {
			fills,
			fontColors
		};
	}
	static lightenHex(hex, ratio) {
		const m = (hex || "").replace("#", "");
		if (m.length !== 6 || /[^0-9a-fA-F]/.test(m)) return hex || "#FFFFFF";
		const r = parseInt(m.substring(0, 2), 16);
		const g = parseInt(m.substring(2, 4), 16);
		const b = parseInt(m.substring(4, 6), 16);
		const lr = Math.round(r + (255 - r) * ratio);
		const lg = Math.round(g + (255 - g) * ratio);
		const lb = Math.round(b + (255 - b) * ratio);
		const to2 = (n) => n.toString(16).padStart(2, "0");
		return `#${to2(lr)}${to2(lg)}${to2(lb)}`;
	}
	static findTbl(gf) {
		const graphicData = gf.getElementsByTagNameNS("*", "graphicData")[0] ?? null;
		if (!graphicData) return null;
		return graphicData.getElementsByTagNameNS("*", "tbl")[0] ?? null;
	}
	static extractCellText(txBody, themeColors, tc) {
		if (!txBody) return {
			text: "",
			font: {},
			align: {},
			padding: {
				left: 0,
				top: 0,
				right: 0,
				bottom: 0
			}
		};
		const bodyPr = txBody.getElementsByTagNameNS("*", "bodyPr")[0] ?? null;
		let vertical = "top";
		const tcPr = tc?.getElementsByTagNameNS("*", "tcPr")[0] ?? null;
		const tcAnchor = tcPr?.getAttribute("anchor") || tcPr?.getAttribute("vAlign") || void 0;
		const bpAnchor = bodyPr?.getAttribute("anchor") || void 0;
		const vSrc = tcAnchor || bpAnchor;
		if (vSrc === "ctr") vertical = "middle";
		else if (vSrc === "b") vertical = "bottom";
		else vertical = "top";
		const lIns = bodyPr?.getAttribute("lIns");
		const tIns = bodyPr?.getAttribute("tIns");
		const rIns = bodyPr?.getAttribute("rIns");
		const bIns = bodyPr?.getAttribute("bIns");
		const padding = {
			left: lIns ? Number(lIns) / 9525 : 6,
			top: tIns ? Number(tIns) / 9525 : 2,
			right: rIns ? Number(rIns) / 9525 : 6,
			bottom: bIns ? Number(bIns) / 9525 : 2
		};
		let horiz = "left";
		let fontName = "Arial";
		let fontSize = 14;
		let color;
		let parts = [];
		const paragraphs = txBody.getElementsByTagNameNS("*", "p");
		for (const p of Array.from(paragraphs)) {
			const algn = (p.getElementsByTagNameNS("*", "pPr")[0] ?? null)?.getAttribute("algn") || void 0;
			if (algn) horiz = algn === "ctr" ? "center" : algn === "r" ? "right" : algn.startsWith("just") ? "justify" : "left";
			for (const child of Array.from(p.childNodes)) if (child.nodeType === 1) {
				const ln = child.localName;
				if (ln === "r") {
					const rPr = child.getElementsByTagNameNS("*", "rPr")[0] ?? null;
					if (rPr) {
						fontName = (rPr.getElementsByTagNameNS("*", "latin")[0] ?? null)?.getAttribute("typeface") ?? fontName;
						const sz = rPr.getAttribute("sz");
						if (sz) fontSize = parseInt(sz, 10) / 100;
						const solidFill = rPr.querySelector("*|solidFill");
						const c = XmlHelper.getColorFromElement(solidFill || null, themeColors);
						if (c) color = c;
					}
					const t = child.getElementsByTagNameNS("*", "t")[0]?.textContent ?? "";
					parts.push(t);
				} else if (ln === "br") parts.push("\n");
				else if (ln === "fld") {
					const runs = child.getElementsByTagNameNS("*", "r");
					for (const r of Array.from(runs)) {
						const t = r.getElementsByTagNameNS("*", "t")[0]?.textContent ?? "";
						parts.push(t);
					}
				}
			}
		}
		return {
			text: parts.join("").trim(),
			font: {
				name: fontName,
				size: fontSize,
				color
			},
			align: {
				horizontal: horiz,
				vertical
			},
			padding
		};
	}
	static extractFillColor(tcPr, themeColors) {
		if (!tcPr) return void 0;
		if (tcPr.getElementsByTagNameNS("*", "noFill")[0]) return void 0;
		const direct = Array.from(tcPr.children).find((c) => c.localName === "solidFill");
		if (direct) return XmlHelper.getColorFromElement(direct, themeColors);
		const all = Array.from(tcPr.getElementsByTagNameNS("*", "solidFill"));
		for (const cand of all) {
			let p = cand.parentElement;
			let insideLine = false;
			while (p && p !== tcPr) {
				const ln = p.localName;
				if (ln === "ln" || ln === "lnL" || ln === "lnR" || ln === "lnT" || ln === "lnB" || ln === "tcBorders") {
					insideLine = true;
					break;
				}
				p = p.parentElement;
			}
			if (!insideLine) {
				const col = XmlHelper.getColorFromElement(cand, themeColors);
				if (col) return col;
			}
		}
	}
	static extractCellBorders(tcPr, themeColors) {
		const borders = {};
		if (!tcPr) return borders;
		const map = {
			lnL: "left",
			lnR: "right",
			lnT: "top",
			lnB: "bottom"
		};
		for (const key of Object.keys(map)) {
			const ln = tcPr.getElementsByTagNameNS("*", key)[0] ?? null;
			if (!ln) continue;
			const wAttr = ln.getAttribute("w");
			const w = wAttr ? Number(wAttr) / 9525 : void 0;
			const solidFill = ln.getElementsByTagNameNS("*", "solidFill")[0] ?? null;
			const color = XmlHelper.getColorFromElement(solidFill, themeColors);
			const dashVal = (ln.getElementsByTagNameNS("*", "prstDash")[0] ?? null)?.getAttribute("val") || "";
			const style = this.mapPrstDashToCss(dashVal);
			borders[map[key]] = {
				color,
				width: w,
				style
			};
		}
		return borders;
	}
	static extractTableStyleFlags(tblPr) {
		if (!tblPr) return {};
		const flags = {};
		for (const k of [
			"firstRow",
			"firstCol",
			"lastRow",
			"lastCol",
			"bandRow",
			"bandCol"
		]) if (tblPr.getAttribute(k) === "1" || tblPr.getAttribute(k) === "true") flags[k] = true;
		return flags;
	}
	static extractTableStyleId(tblPr) {
		if (!tblPr) return void 0;
		const idEl = tblPr.getElementsByTagNameNS("*", "tblStyleId")[0] || tblPr.getElementsByTagNameNS("*", "tblStyle")[0] || null;
		const idAttr = idEl?.getAttribute("val") || void 0;
		const text = idEl?.textContent?.trim() || void 0;
		const direct = tblPr.getAttribute("tblStyle") || void 0;
		return idAttr || text || direct || void 0;
	}
	static extractTableBorders(tblPr, themeColors) {
		if (!tblPr) return void 0;
		const borders = {};
		const tblBorders = tblPr.getElementsByTagNameNS("*", "tblBorders")[0] ?? null;
		if (!tblBorders) return void 0;
		const map = {
			top: "top",
			right: "right",
			bottom: "bottom",
			left: "left",
			insideH: "insideH",
			insideV: "insideV"
		};
		for (const tag of Object.keys(map)) {
			const node = tblBorders.getElementsByTagNameNS("*", tag)[0] ?? null;
			if (!node) continue;
			const ln = node.getElementsByTagNameNS("*", "ln")[0] ?? node;
			const wAttr = ln.getAttribute("w");
			const w = wAttr ? Number(wAttr) / 9525 : void 0;
			const solidFill = ln.getElementsByTagNameNS("*", "solidFill")[0] ?? null;
			const color = XmlHelper.getColorFromElement(solidFill, themeColors);
			const dashVal = (ln.getElementsByTagNameNS("*", "prstDash")[0] ?? null)?.getAttribute("val") || "";
			const style = this.mapPrstDashToCss(dashVal);
			borders[map[tag]] = {
				color,
				width: w,
				style
			};
		}
		return borders;
	}
	static extractTableFill(tblPr, themeColors) {
		if (!tblPr) return void 0;
		const directSolid = Array.from(tblPr.children).find((c) => c.localName === "solidFill");
		if (directSolid) return XmlHelper.getColorFromElement(directSolid, themeColors);
		const allSolid = Array.from(tblPr.getElementsByTagNameNS("*", "solidFill"));
		for (const cand of allSolid) {
			let p = cand.parentElement;
			let insideBorders = false;
			while (p && p !== tblPr) {
				if (p.localName === "tblBorders") {
					insideBorders = true;
					break;
				}
				p = p.parentElement;
			}
			if (!insideBorders) {
				const col = XmlHelper.getColorFromElement(cand, themeColors);
				if (col) return col;
			}
		}
	}
	static mapPrstDashToCss(val) {
		const v = (val || "").toLowerCase();
		if (!v) return void 0;
		if (v === "solid") return "solid";
		if (v.includes("dot")) return "dotted";
		if (v.includes("dash")) return "dashed";
	}
};
//#endregion
//#region src/elements/ChartExtractor.ts
var ChartExtractor = class {
	static async extract(spTree, relsXml, zip, themeColors) {
		if (!spTree) return [];
		const charts = [];
		const gFrames = spTree.getElementsByTagNameNS("*", "graphicFrame");
		for (const gf of Array.from(gFrames)) {
			const graphicData = gf.getElementsByTagNameNS("*", "graphicData")[0] ?? null;
			if (!graphicData) continue;
			const chartEl = graphicData.getElementsByTagNameNS("*", "chart")[0] ?? null;
			if (!chartEl) continue;
			const rId = chartEl.getAttribute("r:id") || chartEl.getAttribute("r:embed") || void 0;
			if (!rId) continue;
			const target = XmlHelper.findRelationshipById(relsXml, rId)?.getAttribute("Target") || void 0;
			if (!target) continue;
			const fullPath = this.resolvePath(target, "ppt/slides");
			const file = zip.file(fullPath);
			if (!file) continue;
			const xmlStr = await file.async("string");
			const doc = XmlHelper.parseXml(xmlStr);
			const parsed = this.parseChart(doc, themeColors);
			if (!parsed) continue;
			const { x, y, cx, cy, rotationDeg } = XmlHelper.getAbsoluteTransform(gf, spTree);
			charts.push({
				type: "chart",
				zIndex: XmlHelper.getZIndex(gf, spTree),
				rotationDeg,
				chartType: parsed.type,
				position: {
					x,
					y
				},
				size: {
					width: cx,
					height: cy
				},
				categories: parsed.categories,
				series: parsed.series,
				palette: parsed.palette,
				title: parsed.title,
				showLegend: parsed.showLegend,
				showDataLabels: parsed.showDataLabels,
				stackedMode: parsed.stackedMode,
				valueFormat: parsed.valueFormat
			});
		}
		return charts;
	}
	static resolvePath(target, baseDir) {
		if (target.startsWith("/")) return target.slice(1);
		const parts = (baseDir + "/" + target).split("/");
		const resolved = [];
		for (const part of parts) if (part === "..") resolved.pop();
		else if (part !== "." && part !== "") resolved.push(part);
		return resolved.join("/");
	}
	static parseChart(doc, themeColors) {
		const plotArea = doc.getElementsByTagNameNS("*", "plotArea")[0] || null;
		if (!plotArea) return null;
		const titleText = this.extractTitle(doc);
		const showLegend = !!doc.getElementsByTagNameNS("*", "legend")[0];
		const showDataLabels = !!plotArea.getElementsByTagNameNS("*", "dLbls")[0];
		const bar = plotArea.getElementsByTagNameNS("*", "barChart")[0] || null;
		const line = plotArea.getElementsByTagNameNS("*", "lineChart")[0] || null;
		const area = plotArea.getElementsByTagNameNS("*", "areaChart")[0] || null;
		const pie = plotArea.getElementsByTagNameNS("*", "pieChart")[0] || null;
		const doughnut = plotArea.getElementsByTagNameNS("*", "doughnutChart")[0] || null;
		const scatter = plotArea.getElementsByTagNameNS("*", "scatterChart")[0] || null;
		const chartNumFmt = plotArea.getElementsByTagNameNS("*", "dLbls")[0]?.getElementsByTagNameNS("*", "numFmt")[0]?.getAttribute("formatCode") || void 0;
		const palette = [
			themeColors["accent1"],
			themeColors["accent2"],
			themeColors["accent3"],
			themeColors["accent4"],
			themeColors["accent5"],
			themeColors["accent6"]
		].filter(Boolean);
		if (bar) {
			const cat = this.extractCategories(bar) || [];
			const ser = this.extractSeries(bar, themeColors) || [];
			const type = (bar.getElementsByTagNameNS("*", "barDir")[0]?.getAttribute("val") || "col") === "bar" ? "bar" : "column";
			const grouping = bar.getElementsByTagNameNS("*", "grouping")[0]?.getAttribute("val") || "clustered";
			return {
				type,
				categories: cat,
				series: ser,
				palette,
				title: titleText,
				showLegend,
				showDataLabels,
				stackedMode: grouping === "stacked" ? "stacked" : grouping === "percentStacked" ? "percent" : "none",
				valueFormat: chartNumFmt
			};
		}
		if (line) {
			const cat = this.extractCategories(line) || [];
			const ser = this.extractSeries(line, themeColors) || [];
			const grouping = line.getElementsByTagNameNS("*", "grouping")[0]?.getAttribute("val") || "standard";
			return {
				type: "line",
				categories: cat,
				series: ser,
				palette,
				title: titleText,
				showLegend,
				showDataLabels,
				stackedMode: grouping === "stacked" ? "stacked" : grouping === "percentStacked" ? "percent" : "none",
				valueFormat: chartNumFmt
			};
		}
		if (area) {
			const cat = this.extractCategories(area) || [];
			const ser = this.extractSeries(area, themeColors) || [];
			const grouping = area.getElementsByTagNameNS("*", "grouping")[0]?.getAttribute("val") || "standard";
			return {
				type: "area",
				categories: cat,
				series: ser,
				palette,
				title: titleText,
				showLegend,
				showDataLabels,
				stackedMode: grouping === "stacked" ? "stacked" : grouping === "percentStacked" ? "percent" : "none",
				valueFormat: chartNumFmt
			};
		}
		if (pie || doughnut) {
			const chartEl = pie || doughnut;
			return {
				type: "pie",
				categories: this.extractCategories(chartEl) || [],
				series: this.extractSeries(chartEl, themeColors) || [],
				palette,
				title: titleText,
				showLegend,
				showDataLabels,
				stackedMode: "none",
				valueFormat: chartNumFmt
			};
		}
		if (scatter) return {
			type: "scatter",
			categories: [],
			series: this.extractScatterSeries(scatter, themeColors) || [],
			palette,
			title: titleText,
			showLegend,
			showDataLabels,
			stackedMode: "none",
			valueFormat: chartNumFmt
		};
		return null;
	}
	static extractTitle(doc) {
		const title = doc.getElementsByTagNameNS("*", "title")[0] || null;
		if (!title) return void 0;
		const tx = title.getElementsByTagNameNS("*", "tx")[0] || null;
		const rich = tx?.getElementsByTagNameNS("*", "rich")[0] || null;
		if (rich) return Array.from(rich.getElementsByTagNameNS("*", "t")).map((t) => t.textContent || "").join("") || void 0;
		return tx?.getElementsByTagNameNS("*", "v")[0]?.textContent || void 0;
	}
	static extractCategories(parent) {
		const cat = parent.getElementsByTagNameNS("*", "cat")[0] || null;
		if (!cat) return null;
		const strCache = cat.getElementsByTagNameNS("*", "strCache")[0] || null;
		if (strCache) {
			const pts = Array.from(strCache.getElementsByTagNameNS("*", "pt"));
			return this.indexedValues(pts, (p) => p.getElementsByTagNameNS("*", "v")[0]?.textContent || "");
		}
		const numCache = cat.getElementsByTagNameNS("*", "numCache")[0] || null;
		if (numCache) {
			const pts = Array.from(numCache.getElementsByTagNameNS("*", "pt"));
			return this.indexedValues(pts, (p) => Number(p.getElementsByTagNameNS("*", "v")[0]?.textContent || 0));
		}
		const multiLvlStrCache = cat.getElementsByTagNameNS("*", "multiLvlStrCache")[0] || null;
		if (multiLvlStrCache) {
			const firstLvl = multiLvlStrCache.getElementsByTagNameNS("*", "lvl")[0] || null;
			if (firstLvl) {
				const pts = Array.from(firstLvl.getElementsByTagNameNS("*", "pt"));
				return this.indexedValues(pts, (p) => p.getElementsByTagNameNS("*", "v")[0]?.textContent || "");
			}
		}
		return null;
	}
	static extractSeries(parent, themeColors) {
		const series = [];
		const sers = Array.from(parent.getElementsByTagNameNS("*", "ser"));
		let idx = 0;
		for (const s of sers) {
			const name = s.getElementsByTagNameNS("*", "tx")[0]?.getElementsByTagNameNS("*", "v")[0]?.textContent || void 0;
			const numCache = s.getElementsByTagNameNS("*", "numCache")[0] || null;
			let values = [];
			if (numCache) {
				const pts = Array.from(numCache.getElementsByTagNameNS("*", "pt"));
				values = this.indexedValues(pts, (p) => Number(p.getElementsByTagNameNS("*", "v")[0]?.textContent || 0));
			}
			const valueFormat = s.getElementsByTagNameNS("*", "dLbls")[0]?.getElementsByTagNameNS("*", "numFmt")[0]?.getAttribute("formatCode") || void 0;
			const solidFill = (s.getElementsByTagNameNS("*", "spPr")[0] || null)?.getElementsByTagNameNS("*", "solidFill")[0] || null;
			const color = XmlHelper.getColorFromElement(solidFill, themeColors);
			const ptColors = [];
			for (const dp of Array.from(s.getElementsByTagNameNS("*", "dPt"))) {
				const di = Number(dp.getElementsByTagNameNS("*", "idx")[0]?.getAttribute("val") || 0);
				const df = (dp.getElementsByTagNameNS("*", "spPr")[0] || null)?.getElementsByTagNameNS("*", "solidFill")[0] || null;
				const dc = XmlHelper.getColorFromElement(df, themeColors);
				if (dc) ptColors[di] = dc;
			}
			series.push({
				name,
				values,
				color,
				valueFormat,
				ptColors: ptColors.length > 0 ? ptColors : void 0
			});
			idx += 1;
		}
		return series;
	}
	static extractScatterSeries(parent, themeColors) {
		const out = [];
		const sers = Array.from(parent.getElementsByTagNameNS("*", "ser"));
		for (const s of sers) {
			const name = s.getElementsByTagNameNS("*", "tx")[0]?.getElementsByTagNameNS("*", "v")[0]?.textContent || void 0;
			const xCache = s.getElementsByTagNameNS("*", "xVal")[0]?.getElementsByTagNameNS("*", "numCache")[0] || null;
			const yCache = s.getElementsByTagNameNS("*", "yVal")[0]?.getElementsByTagNameNS("*", "numCache")[0] || null;
			const xPts = xCache ? Array.from(xCache.getElementsByTagNameNS("*", "pt")) : [];
			const yPts = yCache ? Array.from(yCache.getElementsByTagNameNS("*", "pt")) : [];
			const xValues = this.indexedValues(xPts, (p) => Number(p.getElementsByTagNameNS("*", "v")[0]?.textContent || 0));
			const yValues = this.indexedValues(yPts, (p) => Number(p.getElementsByTagNameNS("*", "v")[0]?.textContent || 0));
			const len = Math.max(xValues.length, yValues.length);
			const points = [];
			for (let i = 0; i < len; i++) if (xValues[i] !== void 0 && yValues[i] !== void 0) points.push({
				x: xValues[i],
				y: yValues[i]
			});
			const solidFill = (s.getElementsByTagNameNS("*", "spPr")[0] || null)?.getElementsByTagNameNS("*", "solidFill")[0] || null;
			const color = XmlHelper.getColorFromElement(solidFill, themeColors);
			const valueFormat = s.getElementsByTagNameNS("*", "dLbls")[0]?.getElementsByTagNameNS("*", "numFmt")[0]?.getAttribute("formatCode") || void 0;
			out.push({
				name,
				points,
				color,
				valueFormat
			});
		}
		return out;
	}
	/** OOXML caches address points by `idx`; XML order is not guaranteed. */
	static indexedValues(points, value) {
		let max = -1;
		const indexed = [];
		points.forEach((point, fallbackIndex) => {
			const raw = point.getAttribute("idx");
			const index = raw !== null && /^\d+$/.test(raw) ? Number(raw) : fallbackIndex;
			max = Math.max(max, index);
			indexed.push({
				index,
				value: value(point)
			});
		});
		const result = new Array(max + 1);
		for (const point of indexed) result[point.index] = point.value;
		return result;
	}
};
//#endregion
//#region src/core/SlideExtractor.ts
/**
* Responsible for extracting all slides from the .pptx file as lists of SlideElement.
*/
var SlideExtractor = class {
	zip;
	constructor(zip) {
		this.zip = zip;
	}
	/**
	* Extracts all slides in order and parses their visual elements.
	* @returns An array of SlideElement lists (one per slide).
	*/
	async extractSlides() {
		const themeXmlStr = await this.zip.file("ppt/theme/theme1.xml")?.async("string");
		const themeXml = themeXmlStr ? XmlHelper.parseXml(themeXmlStr) : null;
		const themeColors = XmlHelper.extractThemeColors(themeXml);
		const themeTableStyles = XmlHelper.extractThemeTableStyles(themeXml);
		const fallbackSlidePaths = Object.keys(this.zip.files).filter((f) => /^ppt\/slides\/slide\d+\.xml$/.test(f)).sort((a, b) => {
			return parseInt(a.match(/slide(\d+)\.xml$/)?.[1] || "0", 10) - parseInt(b.match(/slide(\d+)\.xml$/)?.[1] || "0", 10);
		});
		const slidePaths = await this.getSlidePathsInPresentationOrder(fallbackSlidePaths);
		const slides = [];
		for (const slidePath of slidePaths) {
			const slideXmlStr = await this.zip.file(slidePath).async("string");
			const slideXml = XmlHelper.parseXml(slideXmlStr);
			const spTree = slideXml.querySelector("p\\:spTree") || slideXml.getElementsByTagNameNS("*", "spTree")[0];
			if (!spTree) {
				console.warn(`Warning: no <spTree> found in ${slidePath}`);
				slides.push([]);
				continue;
			}
			const relsPath = slidePath.replace("slides/", "slides/_rels/") + ".rels";
			const relsXml = this.zip.file(relsPath) ? XmlHelper.parseXml(await this.zip.file(relsPath).async("string")) : XmlHelper.parseXml(`<Relationships/>`);
			const layoutTarget = XmlHelper.findRelationshipByTypeSuffix(relsXml, "/slideLayout")?.getAttribute("Target") || void 0;
			let layoutSpTree = null;
			let layoutRelsXml = null;
			if (layoutTarget) {
				const layoutPath = this.resolvePath(layoutTarget, "ppt/slides");
				const layoutXmlStr = await this.zip.file(layoutPath)?.async("string");
				if (layoutXmlStr) {
					const layoutXml = XmlHelper.parseXml(layoutXmlStr);
					layoutSpTree = layoutXml.querySelector("p\\:spTree") || layoutXml.getElementsByTagNameNS("*", "spTree")[0] || null;
					const layoutRelsPath = layoutPath.replace("slideLayouts/", "slideLayouts/_rels/") + ".rels";
					layoutRelsXml = this.zip.file(layoutRelsPath) ? XmlHelper.parseXml(await this.zip.file(layoutRelsPath).async("string")) : XmlHelper.parseXml(`<Relationships/>`);
				}
			}
			let masterSpTree = null;
			let masterRelsXml = null;
			if (layoutRelsXml) {
				const masterTarget = XmlHelper.findRelationshipByTypeSuffix(layoutRelsXml, "/slideMaster")?.getAttribute("Target") || void 0;
				if (masterTarget) {
					const masterPath = this.resolvePath(masterTarget, "ppt/slideLayouts");
					const masterXmlStr = await this.zip.file(masterPath)?.async("string");
					if (masterXmlStr) {
						const masterXml = XmlHelper.parseXml(masterXmlStr);
						masterSpTree = masterXml.querySelector("p\\:spTree") || masterXml.getElementsByTagNameNS("*", "spTree")[0] || null;
						const masterRelsPath = masterPath.replace("slideMasters/", "slideMasters/_rels/") + ".rels";
						masterRelsXml = this.zip.file(masterRelsPath) ? XmlHelper.parseXml(await this.zip.file(masterRelsPath).async("string")) : XmlHelper.parseXml(`<Relationships/>`);
					}
				}
			}
			const slideBg = await this.extractBackground(slideXml, relsXml, "ppt/slides", this.zip, themeColors);
			const layoutBg = layoutRelsXml ? await this.extractBackground(layoutSpTree?.ownerDocument || null, layoutRelsXml, "ppt/slideLayouts", this.zip, themeColors) : null;
			const masterBg = masterRelsXml ? await this.extractBackground(masterSpTree?.ownerDocument || null, masterRelsXml, "ppt/slideMasters", this.zip, themeColors) : null;
			const bgElement = slideBg || layoutBg || masterBg;
			if (bgElement) bgElement.zIndex = -1;
			const masterText = masterSpTree ? TextExtractor.extract(masterSpTree, themeColors, { context: "master" }) : [];
			const masterImages = masterSpTree && masterRelsXml ? await ImageExtractor.extract(masterSpTree, masterRelsXml, this.zip, "ppt/slideMasters") : [];
			const masterShapes = masterSpTree ? ShapeExtractor.extract(masterSpTree, themeColors) : [];
			const layoutText = layoutSpTree ? TextExtractor.extract(layoutSpTree, themeColors, { context: "layout" }) : [];
			const layoutImages = layoutSpTree && layoutRelsXml ? await ImageExtractor.extract(layoutSpTree, layoutRelsXml, this.zip, "ppt/slideLayouts") : [];
			const layoutShapes = layoutSpTree ? ShapeExtractor.extract(layoutSpTree, themeColors) : [];
			const masterGeom = this.extractPlaceholderGeom(masterSpTree);
			const layoutGeom = this.extractPlaceholderGeom(layoutSpTree);
			const mergedGeom = {
				...masterGeom,
				...layoutGeom
			};
			const masterTypes = this.extractPlaceholderTypes(masterSpTree);
			const layoutTypes = this.extractPlaceholderTypes(layoutSpTree);
			const mergedTypes = {
				...masterTypes,
				...layoutTypes
			};
			const slideText = TextExtractor.extract(spTree, themeColors, {
				context: "slide",
				placeholderGeom: mergedGeom,
				placeholderTypes: mergedTypes
			});
			const slideImages = await ImageExtractor.extract(spTree, relsXml, this.zip, "ppt/slides");
			const slideTables = TableExtractor.extract(spTree, themeColors, themeTableStyles);
			const slideCharts = await ChartExtractor.extract(spTree, relsXml, this.zip, themeColors);
			const slideShapes = ShapeExtractor.extract(spTree, themeColors);
			const withLayer = (items, layer) => items.map((item) => ({
				...item,
				zIndex: layer + (item.zIndex ?? 0)
			}));
			slides.push([
				...bgElement ? [bgElement] : [],
				...withLayer(masterShapes, 0),
				...withLayer(masterImages, 0),
				...withLayer(masterText, 0),
				...withLayer(layoutShapes, 1e4),
				...withLayer(layoutImages, 1e4),
				...withLayer(layoutText, 1e4),
				...withLayer(slideShapes, 2e4),
				...withLayer(slideTables, 2e4),
				...withLayer(slideCharts, 2e4),
				...withLayer(slideImages, 2e4),
				...withLayer(slideText, 2e4)
			]);
		}
		return slides;
	}
	/** Normalize a relative path against a base directory inside ppt folder */
	resolvePath(target, baseDir) {
		if (target.startsWith("/")) return target.slice(1);
		const parts = (baseDir + "/" + target).split("/");
		const resolved = [];
		for (const part of parts) if (part === "..") {
			if (resolved.length) resolved.pop();
		} else if (part !== "." && part !== "") resolved.push(part);
		return resolved.join("/");
	}
	/** Build an `idx → type` map from layout/master placeholders so slide-level
	*  shapes that only carry `idx` can resolve their inherited placeholder
	*  type (e.g. "body", "title"). */
	extractPlaceholderTypes(spTree) {
		const map = {};
		if (!spTree) return map;
		const shapes = spTree.getElementsByTagNameNS("*", "sp");
		for (const shape of Array.from(shapes)) {
			const ph = (shape.getElementsByTagNameNS("*", "nvPr")[0] ?? null)?.getElementsByTagNameNS("*", "ph")[0] ?? null;
			if (!ph) continue;
			const idx = ph.getAttribute("idx") || void 0;
			const type = ph.getAttribute("type") || void 0;
			if (idx && type) map[idx] = type;
		}
		return map;
	}
	extractPlaceholderGeom(spTree) {
		const map = {};
		if (!spTree) return map;
		const shapes = spTree.getElementsByTagNameNS("*", "sp");
		for (const shape of Array.from(shapes)) {
			const ph = (shape.getElementsByTagNameNS("*", "nvPr")[0] ?? null)?.getElementsByTagNameNS("*", "ph")[0] ?? null;
			const idx = ph?.getAttribute("idx") || void 0;
			const type = ph?.getAttribute("type") || void 0;
			const key = idx || (type ? "type:" + type : void 0);
			if (!key) continue;
			const xfrm = shape.getElementsByTagNameNS("*", "xfrm")[0] ?? null;
			const off = xfrm?.getElementsByTagNameNS("*", "off")[0] ?? null;
			const ext = xfrm?.getElementsByTagNameNS("*", "ext")[0] ?? null;
			if (!off || !ext) continue;
			const geom = {
				x: XmlHelper.getAttrAsNumber(off, "x"),
				y: XmlHelper.getAttrAsNumber(off, "y"),
				cx: XmlHelper.getAttrAsNumber(ext, "cx"),
				cy: XmlHelper.getAttrAsNumber(ext, "cy")
			};
			map[key] = geom;
			if (type === "title") map["type:ctrTitle"] = geom;
			else if (type === "ctrTitle") map["type:title"] = geom;
		}
		return map;
	}
	async extractBackground(doc, rels, baseDir, zip, themeColors) {
		if (!doc) return null;
		const bg = doc.getElementsByTagNameNS("*", "bg")[0];
		if (!bg) return null;
		const bgPr = bg.getElementsByTagNameNS("*", "bgPr")[0] || null;
		const solidFill = bgPr?.getElementsByTagNameNS("*", "solidFill")[0] || null;
		const color = XmlHelper.getColorFromElement(solidFill, themeColors);
		if (color) return {
			type: "background",
			fillColor: color
		};
		const schemeVal = ((bg.getElementsByTagNameNS("*", "bgRef")[0] || null)?.getElementsByTagNameNS("*", "schemeClr")[0] || null)?.getAttribute("val") || void 0;
		if (schemeVal && themeColors[schemeVal]) return {
			type: "background",
			fillColor: themeColors[schemeVal]
		};
		const embedId = ((bgPr?.getElementsByTagNameNS("*", "blipFill")[0] || null)?.getElementsByTagNameNS("*", "blip")[0] || null)?.getAttribute("r:embed") || void 0;
		if (embedId && rels) {
			const target = XmlHelper.findRelationshipById(rels, embedId)?.getAttribute("Target") || void 0;
			if (target) {
				const fullPath = this.resolvePath(target, baseDir);
				const file = zip.file(fullPath);
				if (file) {
					const binary = await file.async("base64");
					return {
						type: "background",
						imageSrc: `data:${imageMimeType(fullPath)};base64,${binary}`
					};
				}
			}
		}
		return null;
	}
	/**
	* Slides are displayed according to `presentation.xml`'s relationship list,
	* not according to the numeric suffix in their file names. The latter is
	* commonly sequential but changes after slides are moved or duplicated.
	*/
	async getSlidePathsInPresentationOrder(fallback) {
		const presentation = this.zip.file("ppt/presentation.xml");
		const relationships = this.zip.file("ppt/_rels/presentation.xml.rels");
		if (!presentation || !relationships) return fallback;
		try {
			const [presentationXml, relationshipsXml] = await Promise.all([presentation.async("string"), relationships.async("string")]);
			const doc = XmlHelper.parseXml(presentationXml);
			const rels = XmlHelper.parseXml(relationshipsXml);
			const ordered = [];
			const slideIds = Array.from(doc.getElementsByTagNameNS("*", "sldId"));
			if (slideIds.length === 0) return fallback;
			for (const slideId of slideIds) {
				const relationshipId = slideId.getAttribute("r:id");
				if (!relationshipId) continue;
				const target = XmlHelper.findRelationshipById(rels, relationshipId)?.getAttribute("Target");
				if (!target) continue;
				const path = this.resolvePath(target, "ppt");
				if (this.zip.file(path) && !ordered.includes(path)) ordered.push(path);
			}
			return ordered;
		} catch {
			return fallback;
		}
	}
};
//#endregion
//#region src/core/PptxReader.ts
/**
* Handles loading a .pptx file and extracting slide elements from it.
*/
var PptxReader = class {
	zip;
	baseWidthPx;
	baseHeightPx;
	/**
	* Loads and parses a .pptx binary buffer.
	* @param buffer The binary content of a .pptx file.
	* @returns A list of slides, each represented as an array of SlideElement.
	*/
	async load(buffer) {
		this.zip = await JSZip.loadAsync(buffer);
		await this.computeSlideBaseSize();
		return new SlideExtractor(this.zip).extractSlides();
	}
	/**
	* Returns slide base size in pixels derived from ppt/presentation.xml sldSz (if available).
	* Defaults to 960x540 when not found.
	*/
	async getBaseSizePx() {
		if (this.baseWidthPx && this.baseHeightPx) return {
			width: this.baseWidthPx,
			height: this.baseHeightPx
		};
		await this.computeSlideBaseSize();
		return {
			width: Number.isFinite(this.baseWidthPx) && this.baseWidthPx > 0 ? this.baseWidthPx : 960,
			height: Number.isFinite(this.baseHeightPx) && this.baseHeightPx > 0 ? this.baseHeightPx : 540
		};
	}
	async computeSlideBaseSize() {
		try {
			if (!this.zip) return;
			const presFile = this.zip.file("ppt/presentation.xml");
			if (!presFile) return;
			const xmlStr = await presFile.async("string");
			const sldSz = XmlHelper.parseXml(xmlStr).getElementsByTagNameNS("*", "sldSz")[0];
			const cx = sldSz ? Number(sldSz.getAttribute("cx") || 0) : 0;
			const cy = sldSz ? Number(sldSz.getAttribute("cy") || 0) : 0;
			if (Number.isFinite(cx) && Number.isFinite(cy) && cx > 0 && cy > 0) {
				this.baseWidthPx = cx / 9525;
				this.baseHeightPx = cy / 9525;
			}
		} catch {}
	}
};
//#endregion
//#region src/renderer/renderTextElement.ts
/**
* Renders a text element as an absolutely positioned HTML <div>.
* @param el Text element to render.
* @returns HTML string representing the text element.
*/
function renderTextElement(el) {
	const nf = (n, fb = 0) => Number.isFinite(n) ? n : fb;
	const x = nf(el.position?.x, 0) / 9525;
	const y = nf(el.position?.y, 0) / 9525;
	const w = nf(el.size?.width, 0) / 9525;
	const h = nf(el.size?.height, 0) / 9525;
	const pad = el.padding || {
		left: 0,
		top: 0,
		right: 0,
		bottom: 0
	};
	const textAlign = el.align?.horizontal || "left";
	const justify = el.align?.vertical === "middle" ? "center" : el.align?.vertical === "bottom" ? "flex-end" : "flex-start";
	const inner = el.html ? el.html : escape$2(el.content);
	const rotation = Number.isFinite(el.rotationDeg) && el.rotationDeg ? `transform:rotate(${el.rotationDeg}deg); transform-origin:center;` : "";
	return `<div style="
    position: absolute;
    left: ${x}px;
    top: ${y}px;
    width: ${w}px;
    height: ${h}px;
    box-sizing: border-box;
    z-index: ${Number.isFinite(el.zIndex) ? el.zIndex : 0};
    display: flex;
    flex-direction: column;
    justify-content: ${justify};
    text-align: ${textAlign};
    padding: ${pad.top}px ${pad.right}px ${pad.bottom}px ${pad.left}px;
    font-family: ${safeFontFamily(el.font?.name)};
    font-size: ${nf(Number(el.font?.size), 12)}pt;
    font-weight: ${el.font?.weight || "normal"};
    color: ${safeColor(el.font?.color, "#000")};
    overflow: hidden;
    white-space: pre-wrap;
    ${rotation}
  ">${inner}</div>`;
}
function escape$2(str) {
	return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}
//#endregion
//#region src/renderer/renderImageElement.ts
/**
* Renders an image element as an absolutely positioned <img> tag.
* @param el Image element to render.
* @returns HTML string representing the image element.
*/
function renderImageElement(el) {
	const nf = (n, fb = 0) => Number.isFinite(n) ? n : fb;
	const x = nf(el.position?.x, 0) / 9525;
	const y = nf(el.position?.y, 0) / 9525;
	const width = nf(el.size?.width, 0) / 9525;
	const height = nf(el.size?.height, 0) / 9525;
	const zIndex = Number.isFinite(el.zIndex) ? el.zIndex : 0;
	const rotation = Number.isFinite(el.rotationDeg) && el.rotationDeg ? `transform:rotate(${el.rotationDeg}deg); transform-origin:center;` : "";
	const src = escapeAttribute(el.src);
	const crop = el.crop;
	if (crop && crop.left + crop.right < 1e5 && crop.top + crop.bottom < 1e5) {
		const visibleX = 1e5 - crop.left - crop.right;
		const visibleY = 1e5 - crop.top - crop.bottom;
		const imageWidth = width * 1e5 / visibleX;
		const imageHeight = height * 1e5 / visibleY;
		return `<div style="position:absolute; z-index:${zIndex}; left:${x}px; top:${y}px; width:${width}px; height:${height}px; overflow:hidden; ${rotation}">
      <img src="${src}" style="position:absolute; left:${-imageWidth * crop.left / 1e5}px; top:${-imageHeight * crop.top / 1e5}px; width:${imageWidth}px; height:${imageHeight}px;" />
    </div>`;
	}
	return `<img src="${src}" style="
    position: absolute;
    z-index: ${zIndex};
    left: ${x}px;
    top: ${y}px;
    width: ${width}px;
    height: ${height}px;
    object-fit: fill;
    ${rotation}
  " />`;
}
//#endregion
//#region src/renderer/shapePathMap.ts
/**
* Maps known PowerPoint shape types to SVG path definitions.
* Supports arrows, connectors, and geometric shapes.
* @param type The shape type from the pptx.
* @returns SVG path string to use in <path> or <polyline>, etc.
*/
function getSvgPathForShape(type) {
	switch (type) {
		case "rightArrow":
		case "arrow": return "POLYGON 0,25 70,25 70,0 100,50 70,100 70,75 0,75";
		case "leftArrow": return "POLYGON 100,25 30,25 30,0 0,50 30,100 30,75 100,75";
		case "leftRightArrow": return "POLYGON 0,50 30,0 30,25 70,25 70,0 100,50 70,100 70,75 30,75 30,100";
		case "triangle": return "POLYGON 50,0 100,100 0,100";
		case "star5": return "POLYGON 50,0 61,35 98,35 68,57 79,91 50,70 21,91 32,57 2,35 39,35";
		case "cloud": return "PATH M20,60 C10,60 10,40 25,40 C30,20 50,20 55,35 C70,30 80,40 80,50 C90,50 90,70 75,70 H25 Z";
		case "straightConnector1": return "LINE_ARROW 0,50 100,50";
		case "bentConnector2": return "POLYLINE 0,50 50,50 50,100";
		case "bentConnector3": return "POLYLINE_ARROW 0,50 40,50 40,70 100,70";
		case "bentConnector4": return "POLYLINE 0,30 30,30 30,70 70,70 70,100";
		case "bentConnector5": return "POLYLINE 0,20 30,20 30,50 60,50 60,80 100,80";
		case "curvedConnector2": return "PATH M0,50 Q50,0 100,50";
		case "curvedConnector3": return "PATH M0,50 Q25,0 50,50 Q75,100 100,50";
		case "curvedConnector4": return "PATH M0,40 Q20,0 40,40 Q60,80 80,40 Q90,20 100,40";
		case "curvedConnector5": return "PATH M0,50 Q20,20 40,50 Q60,80 80,50 Q90,40 100,50";
		case "bentArrow": return "POLYGON 0,0 70,0 70,30 100,30 50,100 50,30 0,30";
		case "notchedRightArrow": return "POLYGON 0,20 60,20 60,0 100,50 60,100 60,80 0,80";
		case "curvedRightArrow": return "PATH M0,50 Q50,0 100,50 Q50,100 0,50 Z";
		default: return "POLYGON 0,0 100,0 100,100 0,100";
	}
}
//#endregion
//#region src/renderer/renderShapeElement.ts
/**
* Renders a shape element as an absolutely positioned HTML or SVG element.
* Supports all recognized PPTX shape types using SVG when necessary.
* @param el Shape element to render.
* @returns HTML string representing the shape.
*/
function renderShapeElement(el, options = {}) {
	const nf = (n, fb = 0) => Number.isFinite(n) ? n : fb;
	const x = nf(el.position?.x, 0) / 9525;
	const y = nf(el.position?.y, 0) / 9525;
	const width = nf(el.size?.width, 0) / 9525;
	const height = nf(el.size?.height, 0) / 9525;
	const rotation = el.rotationDeg && !isNaN(el.rotationDeg) ? el.rotationDeg : 0;
	const rotationStyle = rotation ? `transform: rotate(${rotation}deg); transform-origin: center;` : "";
	const fillColor = safeColor(el.fillColor);
	const borderColor = safeColor(el.borderColor);
	const borderWidth = el.strokeWidth && Number.isFinite(el.strokeWidth) ? el.strokeWidth : 1;
	const style = `
    position: absolute;
    left: ${x}px;
    top: ${y}px;
    width: ${width}px;
    height: ${height}px;
    z-index: ${Number.isFinite(el.zIndex) ? el.zIndex : 0};
    ${rotationStyle}
  `;
	if (el.shapeType === "rect") return `<div style="${style}
      background-color: ${fillColor};
      border: ${borderWidth}px solid ${borderColor};
      box-sizing: border-box;"></div>`;
	if (el.shapeType === "ellipse") return `<div style="${style}
      background-color: ${fillColor};
      border: ${borderWidth}px solid ${borderColor};
      border-radius: 50%;
      box-sizing: border-box;"></div>`;
	if (el.shapeType === "roundRect") return `<div style="${style}
      background-color: ${fillColor};
      border: ${borderWidth}px solid ${borderColor};
      border-radius: 16px;
      box-sizing: border-box;"></div>`;
	return shapeSvg(x, y, width, height, fillColor, borderColor, getSvgPathForShape(el.shapeType), el.strokeWidth && Number.isFinite(el.strokeWidth) ? el.strokeWidth : void 0, rotation, el.headEnd, el.tailEnd, options.scaleStrokes === true, Number.isFinite(el.zIndex) ? el.zIndex : 0);
}
function shapeSvg(x, y, width, height, fill, stroke, raw, strokeWidthPx, rotationDeg, headEnd, tailEnd, scaleStrokes, zIndex = 0) {
	const strokeColorOpt = resolveStrokeColor(stroke, fill);
	const [typeRaw, ...rest] = raw.trim().split(/\s+/);
	const type = typeRaw.toUpperCase().replace("_ARROW", "");
	const isArrow = typeRaw.endsWith("_ARROW");
	const data = rest.join(" ");
	const svgHeight = height;
	const svgWidth = width;
	const sw = strokeWidthPx && strokeWidthPx > 0 ? strokeWidthPx : 2;
	const rotationStyle = rotationDeg ? `transform: rotate(${rotationDeg}deg); transform-origin: center;` : "";
	const commonStyle = `
    position: absolute;
    left: ${x}px;
    top: ${y}px;
    width: ${svgWidth}px;
    height: ${svgHeight}px;
    z-index: ${zIndex};
    ${rotationStyle}
  `;
	switch (type) {
		case "PATH": {
			const defs = buildMarkerDefs(headEnd, tailEnd, strokeColorOpt || "#000");
			const markerStartAttr = defs.startId ? `marker-start=\"url(#${defs.startId})\"` : "";
			const markerEndAttr = defs.endId ? `marker-end=\"url(#${defs.endId})\"` : "";
			return `<svg viewBox="0 0 100 100" style="${commonStyle}" overflow="visible">
        ${defs.defs}
        <path d="${data}" fill="none" stroke="${strokeColorOpt || "#000"}" stroke-width="${sw}" ${scaleStrokes ? "" : "vector-effect=\"non-scaling-stroke\""} ${markerStartAttr} ${markerEndAttr} />
      </svg>`;
		}
		case "POLYLINE":
		case "LINE": {
			const coords = data.split(/[\s,]+/).map((v) => parseFloat(v)).filter((v) => !isNaN(v));
			if (coords.length < 4 || coords.length % 2 !== 0) {
				if (typeof console !== "undefined" && console.warn) console.warn(`[pptx-to-html] Invalid POLYLINE/LINE shape data: "${data}"`);
				return "";
			}
			const pointPairs = [];
			for (let i = 0; i < coords.length; i += 2) pointPairs.push(`${coords[i]},${coords[i + 1]}`);
			const effectiveWidth = width > 0 ? width : Math.max(sw * 2, 2);
			const effectiveHeight = height > 0 ? height : Math.max(sw * 2, 2);
			const defs = buildMarkerDefs(headEnd, tailEnd ?? (isArrow ? {
				type: "triangle",
				w: "med",
				len: "med"
			} : void 0), strokeColorOpt || "#000");
			const markerStartAttr = defs.startId ? `marker-start=\"url(#${defs.startId})\"` : "";
			const markerEndAttr = defs.endId ? `marker-end=\"url(#${defs.endId})\"` : "";
			const scaledPairs = [];
			for (let i = 0; i < coords.length; i += 2) {
				const px = coords[i] / 100 * effectiveWidth;
				const py = coords[i + 1] / 100 * effectiveHeight;
				scaledPairs.push(`${px},${py}`);
			}
			const scaledPoints = scaledPairs.join(" ");
			return `
        <svg viewBox="0 0 ${effectiveWidth} ${effectiveHeight}"
            style="
              position: absolute;
              left: ${x}px;
              top: ${y}px;
              width: ${effectiveWidth}px;
              height: ${effectiveHeight}px;
              z-index: ${zIndex};
              ${rotationStyle}
            "
            overflow="visible">
          ${defs.defs}
          <polyline points="${scaledPoints}"
                    fill="none"
                    stroke="${strokeColorOpt || "#000"}"
                    stroke-width="${sw}"
                    ${scaleStrokes ? "" : "vector-effect=\"non-scaling-stroke\""}
                    ${markerStartAttr} ${markerEndAttr} />
        </svg>`;
		}
		default: return `<svg viewBox="0 0 100 100" style="${commonStyle}">
        <polygon points="${data}" fill="${fill}" stroke="${strokeColorOpt ?? "none"}" stroke-width="${sw}" ${scaleStrokes ? "" : "vector-effect=\"non-scaling-stroke\""} />
      </svg>`;
	}
}
function buildMarkerDefs(headEnd, tailEnd, color) {
	const parts = [];
	let startId;
	let endId;
	if (headEnd && headEnd.type && headEnd.type !== "none") {
		startId = `mstart-${Math.random().toString(36).slice(2, 8)}`;
		parts.push(markerDef(startId, headEnd, color));
	}
	if (tailEnd && tailEnd.type && tailEnd.type !== "none") {
		endId = `mend-${Math.random().toString(36).slice(2, 8)}`;
		parts.push(markerDef(endId, tailEnd, color));
	}
	return {
		defs: parts.length ? `<defs>${parts.join("\n")}</defs>` : "",
		startId,
		endId
	};
}
function markerDef(id, spec, color) {
	const base = 4 * mapLen(spec.len);
	const refX = base;
	const refY = base / 2;
	switch ((spec.type || "triangle").toLowerCase()) {
		case "diamond": return `<marker id="${id}" markerUnits="strokeWidth" markerWidth="${base}" markerHeight="${base}"
                      refX="${refX}" refY="${refY}" orient="auto-start-reverse">
                <polygon points="${base / 2},0 ${base},${base / 2} ${base / 2},${base} 0,${base / 2}" fill="${color}" />
              </marker>`;
		case "oval": return `<marker id="${id}" markerUnits="strokeWidth" markerWidth="${base}" markerHeight="${base}"
                      refX="${refX}" refY="${refY}" orient="auto-start-reverse">
                <circle cx="${base / 2}" cy="${base / 2}" r="${base / 2}" fill="${color}" />
              </marker>`;
		case "stealth": return `<marker id="${id}" markerUnits="strokeWidth" markerWidth="${base}" markerHeight="${base}"
                      refX="${refX}" refY="${refY}" orient="auto-start-reverse">
                <polygon points="${base},${base / 2} 0,0 0,${base}" fill="${color}" />
              </marker>`;
		default: return `<marker id="${id}" markerUnits="strokeWidth" markerWidth="${base}" markerHeight="${base}"
                      refX="${refX}" refY="${refY}" orient="auto-start-reverse">
                <polygon points="0,0 ${base},${base / 2} 0,${base}" fill="${color}" />
              </marker>`;
	}
}
function mapLen(len) {
	switch ((len || "med").toLowerCase()) {
		case "sm":
		case "small": return 1.5;
		case "lg":
		case "large": return 2.5;
		default: return 2;
	}
}
function resolveStrokeColor(stroke, fill) {
	if (stroke && stroke !== "transparent") return stroke;
	if (fill && fill !== "transparent") return fill;
}
//#endregion
//#region src/renderer/renderTableElement.ts
function renderTableElement(el) {
	const nf = (n, fb = 0) => Number.isFinite(n) ? n : fb;
	const x = nf(el.position?.x, 0) / 9525;
	const y = nf(el.position?.y, 0) / 9525;
	const width = nf(el.size?.width, 0) / 9525;
	const height = nf(el.size?.height, 0) / 9525;
	const rotation = Number.isFinite(el.rotationDeg) && el.rotationDeg ? `transform:rotate(${el.rotationDeg}deg); transform-origin:center;` : "";
	const colWidthsPx = el.columns.map((w) => nf(w, 0) / 9525);
	const colTotal = colWidthsPx.reduce((a, b) => a + b, 0) || 1;
	const cols = colWidthsPx.map((w) => `<col style="width:${w / colTotal * 100}%">`).join("");
	const tableBg = el.tableFillColor || el.style?.fills?.wholeTbl;
	let rowIndex = 0;
	const rowsHtml = el.rows.map((row) => {
		let colIndex = 0;
		const tds = row.cells.map((cell) => {
			const pad = cell.padding || {
				left: 6,
				top: 2,
				right: 6,
				bottom: 2
			};
			const ta = cell.align?.horizontal || "left";
			const va = cell.align?.vertical || "top";
			const borderCss = computeCellBordersCSS(el, cell, rowIndex, colIndex);
			const { bg, fontColor, emphasize } = computeCellStyleFromTableStyle(el, rowIndex, colIndex);
			const isHeaderCol = emphasize;
			const style = `
            padding:${pad.top}px ${pad.right}px ${pad.bottom}px ${pad.left}px;
            text-align:${ta};
            vertical-align:${va === "middle" ? "middle" : va};
            ${cell.fillColor ? `background-color:${safeColor(cell.fillColor)};` : ""}
            ${!cell.fillColor && bg ? `background-color:${safeColor(bg)};` : ""}
            ${cell.font?.color ? `color:${safeColor(cell.font.color, "#000")};` : fontColor ? `color:${safeColor(fontColor, "#000")};` : ""}
            ${cell.font?.name ? `font-family:${safeFontFamily(cell.font.name)};` : ""}
            ${cell.font?.size ? `font-size:${cell.font.size}pt;` : ""}
            ${borderCss}
            ${isHeaderCol ? "font-weight:600;" : ""}
            overflow:hidden; word-break: break-word; white-space: pre-wrap;`;
			const html = `<td${`${cell.colSpan ? ` colspan=\"${cell.colSpan}\"` : ""}${cell.rowSpan ? ` rowspan=\"${cell.rowSpan}\"` : ""}`} style="${style}">${escape$1(cell.text).replace(/\n/g, "<br>")}</td>`;
			colIndex += cell.colSpan || 1;
			return html;
		}).join("");
		const trHtml = `<tr${el.tableStyle?.firstRow && rowIndex === 0 ? " style=\"font-weight:600;\"" : ""}>${tds}</tr>`;
		rowIndex += 1;
		return trHtml;
	}).join("");
	return `<div style="position:absolute; z-index:${Number.isFinite(el.zIndex) ? el.zIndex : 0}; left:${x}px; top:${y}px; width:${width}px; height:${height}px; ${rotation}">
    <table style="border-collapse:collapse; width:100%; height:100%; table-layout:fixed;${tableBg ? ` background-color:${safeColor(tableBg)};` : ""}">
      <colgroup>${cols}</colgroup>
      <tbody>${rowsHtml}</tbody>
    </table>
  </div>`;
}
function computeCellBordersCSS(el, cell, rowIndex, colIndex) {
	const css = [];
	const sides = [
		"top",
		"right",
		"bottom",
		"left"
	];
	const apply = (side, b) => {
		if (!b) return;
		const w = b.width ?? 1;
		const c = safeColor(b.color, "#000");
		const st = b.style === "dashed" || b.style === "dotted" ? b.style : "solid";
		css.push(`border-${side}: ${Math.max(1, Math.round(w))}px ${st} ${c};`);
	};
	for (const s of sides) {
		const b = cell.borders?.[s];
		if (b) apply(s, b);
	}
	const tb = el.tableBorders || {};
	const lastRow = rowIndex === el.rows.length - 1;
	const lastCol = colIndex === el.columns.length - 1;
	if (!cell.borders?.top && rowIndex === 0) apply("top", tb.top);
	if (!cell.borders?.bottom && lastRow) apply("bottom", tb.bottom);
	if (!cell.borders?.left && colIndex === 0) apply("left", tb.left);
	if (!cell.borders?.right && lastCol) apply("right", tb.right);
	if (!cell.borders?.top && rowIndex > 0) apply("top", tb.insideH);
	if (!cell.borders?.left && colIndex > 0) apply("left", tb.insideV);
	return css.join(" ");
}
function escape$1(str) {
	return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}
function computeCellStyleFromTableStyle(el, rowIndex, colIndex) {
	const lastRow = rowIndex === el.rows.length - 1;
	const lastCol = colIndex === el.columns.length - 1;
	const s = el.style || {};
	const fills = s.fills || {};
	const fontColors = s.fontColors || {};
	let emphasize = false;
	if (el.tableStyle?.firstRow && rowIndex === 0) {
		emphasize = true;
		return {
			bg: fills.firstRow || fills.wholeTbl || "#f0f0f0",
			fontColor: fontColors.firstRow || fontColors.wholeTbl,
			emphasize
		};
	}
	if (el.tableStyle?.lastRow && lastRow) return {
		bg: fills.lastRow || fills.wholeTbl || "#f0f0f0",
		fontColor: fontColors.lastRow || fontColors.wholeTbl,
		emphasize
	};
	if (el.tableStyle?.firstCol && colIndex === 0) {
		emphasize = true;
		return {
			bg: fills.firstCol || fills.wholeTbl || "#f0f0f0",
			fontColor: fontColors.firstCol || fontColors.wholeTbl,
			emphasize
		};
	}
	if (el.tableStyle?.lastCol && lastCol) return {
		bg: fills.lastCol || fills.wholeTbl || "#f0f0f0",
		fontColor: fontColors.lastCol || fontColors.wholeTbl,
		emphasize
	};
	if (el.tableStyle?.bandRow) {
		const band = (el.tableStyle?.firstRow ? rowIndex - 1 : rowIndex) % 2 === 0 ? "band1H" : "band2H";
		return {
			bg: fills[band] || fills.wholeTbl || (rowIndex % 2 === 1 ? "#fafafa" : void 0),
			fontColor: fontColors[band] || fontColors.wholeTbl,
			emphasize
		};
	}
	if (el.tableStyle?.bandCol) {
		const band = colIndex % 2 === 0 ? "band1V" : "band2V";
		return {
			bg: fills[band] || fills.wholeTbl || (colIndex % 2 === 1 ? "#fafafa" : void 0),
			fontColor: fontColors[band] || fontColors.wholeTbl,
			emphasize
		};
	}
	return {
		bg: fills.wholeTbl,
		fontColor: fontColors.wholeTbl,
		emphasize
	};
}
//#endregion
//#region src/renderer/renderChartElement.ts
function renderChartElement(el) {
	const nf = (n, fb = 0) => Number.isFinite(n) ? n : fb;
	const x = nf(el.position?.x, 0) / 9525;
	const y = nf(el.position?.y, 0) / 9525;
	const width = Math.max(1, nf(el.size?.width, 0) / 9525);
	const height = Math.max(1, nf(el.size?.height, 0) / 9525);
	const padding = 24;
	const palette = el.palette && el.palette.length > 0 ? el.palette : [
		"#4e79a7",
		"#f28e2b",
		"#e15759",
		"#76b7b2",
		"#59a14f",
		"#edc949"
	];
	let svg = "";
	if (el.chartType === "column" || el.chartType === "bar") svg = renderBarLike(el, width, height, padding, palette);
	else if (el.chartType === "line") svg = renderLine(el, width, height, padding, palette);
	else if (el.chartType === "area") svg = renderArea(el, width, height, padding, palette);
	else if (el.chartType === "pie") svg = renderPie(el, width, height, palette);
	else if (el.chartType === "scatter") svg = renderScatter(el, width, height, padding, palette);
	const zIndex = Number.isFinite(el.zIndex) ? el.zIndex : 0;
	return `${el.title ? `<div style="position:absolute;z-index:${zIndex};left:${x}px;top:${y - 20}px;width:${width}px;text-align:center;font-weight:600;">${escape(el.title)}</div>` : ""}<div style="position:absolute; z-index:${zIndex}; left:${x}px; top:${y}px; width:${width}px; height:${height}px;${Number.isFinite(el.rotationDeg) && el.rotationDeg ? ` transform:rotate(${el.rotationDeg}deg); transform-origin:center;` : ""}">
      <svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
        ${svg}
      </svg>
    </div>`;
}
function renderBarLike(el, width, height, pad, palette) {
	const catCount = Math.max(1, el.categories.length, ...el.series.map((series) => series.values?.length || 0));
	const chartW = width - pad * 2;
	const chartH = height - pad * 2;
	const isHorizontal = el.chartType === "bar";
	const stacked = el.stackedMode && el.stackedMode !== "none";
	const percent = el.stackedMode === "percent";
	const domain = chartDomain(el, catCount, Boolean(stacked), percent);
	const range = Math.max(1e-9, domain.max - domain.min);
	const scale = (v) => (v - domain.min) / range;
	const parts = [];
	if (isHorizontal) {
		parts.push(`<line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
		parts.push(`<line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
		computeTicks(domain.min, domain.max, 4).forEach((t) => {
			const tx = pad + scale(t) * chartW;
			const ty = height - pad;
			parts.push(`<line x1="${tx}" y1="${ty}" x2="${tx}" y2="${ty + 4}" stroke="#999" stroke-width="1" />`);
			parts.push(`<text x="${tx}" y="${ty + 16}" text-anchor="middle" font-size="10" fill="#666">${formatNumber(t, percent ? "0%" : el.valueFormat)}</text>`);
		});
	} else {
		parts.push(`<line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
		parts.push(`<line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
		computeTicks(domain.min, domain.max, 4).forEach((t) => {
			const ty = height - pad - scale(t) * chartH;
			parts.push(`<line x1="${pad - 4}" y1="${ty}" x2="${pad}" y2="${ty}" stroke="#999" stroke-width="1" />`);
			parts.push(`<text x="${pad - 6}" y="${ty + 3}" text-anchor="end" font-size="10" fill="#666">${formatNumber(t, percent ? "0%" : el.valueFormat)}</text>`);
			parts.push(`<line x1="${pad}" y1="${ty}" x2="${width - pad}" y2="${ty}" stroke="#eee" stroke-width="1" />`);
		});
	}
	if (isHorizontal) {
		const catBand = chartH / catCount;
		const barH = Math.max(2, catBand * .8 / (stacked ? 1 : el.series.length));
		el.series.forEach((s, si) => {
			const color = s.color || palette[si % palette.length];
			(s.values || []).forEach((v, ci) => {
				const baseY = pad + ci * catBand + (catBand - (stacked ? barH : el.series.length * barH)) / 2;
				if (stacked) {
					const sum = el.series.reduce((acc, ss) => acc + ((ss.values || [])[ci] || 0), 0) || 1;
					const rawPrev = stackedPrevious(el, si, ci, v);
					const startValue = percent ? rawPrev / sum : rawPrev;
					const endValue = startValue + (percent ? v / sum : v);
					const start = scale(startValue) * chartW;
					const end = scale(endValue) * chartW;
					const x = pad + Math.min(start, end);
					const w = Math.abs(end - start);
					const y = baseY;
					parts.push(`<rect x="${x}" y="${y}" width="${w}" height="${barH}" fill="${color}" />`);
					if (el.showDataLabels) {
						const fmt = s.valueFormat || el.valueFormat;
						parts.push(`<text x="${x + w - 2}" y="${y + barH / 2 + 3}" text-anchor="end" font-size="10" fill="#000">${formatNumber(percent ? v / sum : v, percent ? "0%" : fmt)}</text>`);
					}
				} else {
					const zero = scale(0) * chartW;
					const value = scale(v) * chartW;
					const w = Math.abs(value - zero);
					const y = baseY + si * barH;
					parts.push(`<rect x="${pad + Math.min(zero, value)}" y="${y}" width="${w}" height="${barH}" fill="${color}" />`);
					if (el.showDataLabels) {
						const fmt = s.valueFormat || el.valueFormat;
						parts.push(`<text x="${pad + w + 2}" y="${y + barH / 2 + 3}" font-size="10" fill="#000">${formatNumber(v, fmt)}</text>`);
					}
				}
			});
		});
		const labelSize = Math.max(10, Math.min(12, chartH / (catCount * 2)));
		el.categories.forEach((c, i) => {
			const cy = pad + i * catBand + catBand / 2 + 4;
			parts.push(`<text x="${pad - 8}" y="${cy}" text-anchor="end" font-size="${labelSize}" fill="#333">${escape(String(c))}</text>`);
		});
	} else {
		const catBand = chartW / catCount;
		const barW = Math.max(2, catBand * .8 / (stacked ? 1 : el.series.length));
		el.series.forEach((s, si) => {
			const color = s.color || palette[si % palette.length];
			(s.values || []).forEach((v, ci) => {
				const baseX = pad + ci * catBand + (catBand - (stacked ? barW : el.series.length * barW)) / 2;
				if (stacked) {
					const sum = el.series.reduce((acc, ss) => acc + ((ss.values || [])[ci] || 0), 0) || 1;
					const rawPrev = stackedPrevious(el, si, ci, v);
					const start = scale(percent ? rawPrev / sum : rawPrev) * chartH;
					const end = scale((percent ? rawPrev / sum : rawPrev) + (percent ? v / sum : v)) * chartH;
					const h = Math.abs(end - start);
					const x = baseX;
					const y = height - pad - Math.max(start, end);
					parts.push(`<rect x="${x}" y="${y}" width="${barW}" height="${h}" fill="${color}" />`);
					if (el.showDataLabels) {
						const fmt = s.valueFormat || el.valueFormat;
						parts.push(`<text x="${x + barW / 2}" y="${y - 2}" text-anchor="middle" font-size="10" fill="#000">${formatNumber(percent ? v / sum : v, percent ? "0%" : fmt)}</text>`);
					}
				} else {
					const zero = scale(0) * chartH;
					const value = scale(v) * chartH;
					const h = Math.abs(value - zero);
					const x = baseX + si * barW;
					const y = height - pad - Math.max(zero, value);
					parts.push(`<rect x="${x}" y="${y}" width="${barW}" height="${h}" fill="${color}" />`);
					if (el.showDataLabels) {
						const fmt = s.valueFormat || el.valueFormat;
						parts.push(`<text x="${x + barW / 2}" y="${y - 2}" text-anchor="middle" font-size="10" fill="#000">${formatNumber(v, fmt)}</text>`);
					}
				}
			});
		});
		const labelSize = Math.max(10, Math.min(12, chartW / (catCount * 4)));
		el.categories.forEach((c, i) => {
			const cx = pad + i * catBand + catBand / 2;
			const cy = height - pad + 14;
			parts.push(`<text x="${cx}" y="${cy}" text-anchor="middle" font-size="${labelSize}" fill="#333">${escape(String(c))}</text>`);
		});
	}
	if (el.showLegend) parts.push(renderLegend(el, width, pad, palette));
	return parts.join("\n");
}
function renderLine(el, width, height, pad, palette) {
	const catCount = Math.max(1, el.categories.length, ...el.series.map((series) => series.values?.length || 0));
	const chartW = width - pad * 2;
	const chartH = height - pad * 2;
	const stacked = el.stackedMode && el.stackedMode !== "none";
	const percent = el.stackedMode === "percent";
	const domain = chartDomain(el, catCount, Boolean(stacked), percent);
	const range = Math.max(1e-9, domain.max - domain.min);
	const scale = (v) => (v - domain.min) / range;
	const xStep = chartW / Math.max(1, catCount - 1);
	const parts = [];
	parts.push(`<line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
	parts.push(`<line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
	computeTicks(domain.min, domain.max, 4).forEach((t) => {
		const ty = height - pad - scale(t) * chartH;
		parts.push(`<line x1="${pad - 4}" y1="${ty}" x2="${pad}" y2="${ty}" stroke="#999" stroke-width="1" />`);
		parts.push(`<text x="${pad - 6}" y="${ty + 3}" text-anchor="end" font-size="10" fill="#666">${formatNumber(t, el.valueFormat)}</text>`);
		parts.push(`<line x1="${pad}" y1="${ty}" x2="${width - pad}" y2="${ty}" stroke="#eee" stroke-width="1" />`);
	});
	const totals = percent ? new Array(catCount).fill(0).map((_, i) => el.series.reduce((acc, sr) => acc + ((sr.values || [])[i] || 0), 0)) : void 0;
	el.series.forEach((s, si) => {
		const color = s.color || palette[si % palette.length];
		let d = "";
		(s.values || []).forEach((v, i) => {
			const x = pad + i * xStep;
			let val = v;
			if (stacked) {
				val = stackedPrevious(el, si, i, v) + v;
				if (percent && totals) val = totals[i] ? val / totals[i] : 0;
			}
			const y = height - pad - scale(val) * chartH;
			d += i === 0 ? `M ${x} ${y}` : ` L ${x} ${y}`;
		});
		parts.push(`<path d="${d}" fill="none" stroke="${color}" stroke-width="2" />`);
		if (el.showDataLabels) (s.values || []).forEach((v, i) => {
			const x = pad + i * xStep;
			const basePrev = stacked ? stackedPrevious(el, si, i, v) : 0;
			const dispVal = percent && totals ? v / (totals[i] || 1) : v;
			const plotValue = stacked ? basePrev + v : v;
			const y = height - pad - scale(percent && totals ? totals[i] ? plotValue / totals[i] : 0 : plotValue) * chartH;
			parts.push(`<circle cx="${x}" cy="${y}" r="2.5" fill="${color}" />`);
			const fmt = s.valueFormat || el.valueFormat;
			parts.push(`<text x="${x}" y="${y - 6}" text-anchor="middle" font-size="10" fill="#000">${formatNumber(dispVal, percent ? "0%" : fmt)}</text>`);
		});
	});
	if (el.categories.length > 0) {
		const labelSize = Math.max(10, Math.min(12, chartW / (catCount * 4)));
		el.categories.forEach((c, i) => {
			const cx = pad + i * xStep;
			parts.push(`<text x="${cx}" y="${height - pad + 14}" text-anchor="middle" font-size="${labelSize}" fill="#333">${escape(String(c))}</text>`);
		});
	}
	return parts.join("\n");
}
function renderArea(el, width, height, pad, palette) {
	const catCount = Math.max(1, el.categories.length, ...el.series.map((series) => series.values?.length || 0));
	const chartW = width - pad * 2;
	const chartH = height - pad * 2;
	const stacked = el.stackedMode && el.stackedMode !== "none";
	const percent = el.stackedMode === "percent";
	const domain = chartDomain(el, catCount, Boolean(stacked), percent);
	const range = Math.max(1e-9, domain.max - domain.min);
	const scale = (v) => (v - domain.min) / range;
	const xStep = chartW / Math.max(1, catCount - 1);
	const parts = [];
	parts.push(`<line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
	parts.push(`<line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
	const totals = percent ? new Array(catCount).fill(0).map((_, i) => el.series.reduce((acc, sr) => acc + ((sr.values || [])[i] || 0), 0)) : void 0;
	const positiveBaseline = new Array(catCount).fill(0);
	const negativeBaseline = new Array(catCount).fill(0);
	el.series.forEach((s, si) => {
		const color = s.color || palette[si % palette.length];
		const topY = [];
		const botY = [];
		(s.values || []).forEach((v, i) => {
			const prev = stacked ? v >= 0 ? positiveBaseline[i] : negativeBaseline[i] : 0;
			const val = stacked ? prev + (percent && totals ? totals[i] ? v / totals[i] : 0 : v) : v;
			const top = height - pad - scale(val) * chartH;
			const bottom = height - pad - scale(prev) * chartH;
			topY.push(top);
			botY.push(bottom);
			if (stacked) if (v >= 0) positiveBaseline[i] = percent && totals ? val : prev + v;
			else negativeBaseline[i] = percent && totals ? val : prev + v;
		});
		let d = "";
		for (let i = 0; i < topY.length; i++) {
			const x = pad + i * xStep;
			d += i === 0 ? `M ${x} ${topY[i]}` : ` L ${x} ${topY[i]}`;
		}
		for (let i = botY.length - 1; i >= 0; i--) {
			const x = pad + i * xStep;
			d += ` L ${x} ${botY[i]}`;
		}
		d += " Z";
		parts.push(`<path d="${d}" fill="${color}" fill-opacity="0.6" stroke="none" />`);
	});
	return parts.join("\n");
}
function stackedPrevious(el, seriesIndex, categoryIndex, value) {
	return el.series.slice(0, seriesIndex).reduce((sum, series) => {
		const prior = (series.values || [])[categoryIndex] || 0;
		return sum + (value >= 0 ? Math.max(0, prior) : Math.min(0, prior));
	}, 0);
}
function chartDomain(el, catCount, stacked, percent) {
	if (percent) return {
		min: 0,
		max: 1
	};
	let min = 0;
	let max = 0;
	if (stacked) for (let i = 0; i < catCount; i++) {
		let positive = 0;
		let negative = 0;
		for (const series of el.series) {
			const value = (series.values || [])[i] || 0;
			if (value >= 0) positive += value;
			else negative += value;
		}
		min = Math.min(min, negative);
		max = Math.max(max, positive);
	}
	else for (const series of el.series) for (const value of series.values || []) {
		min = Math.min(min, value);
		max = Math.max(max, value);
	}
	if (min === max) return {
		min: min - 1,
		max: max + 1
	};
	return {
		min,
		max
	};
}
function renderPie(el, width, height, palette) {
	const s0 = el.series[0];
	const values = s0 && s0.values ? s0.values : [];
	const total = values.reduce((a, b) => a + Math.max(0, b), 0) || 1;
	const cx = width / 2;
	const cy = height / 2;
	const r = Math.min(width, height) * .35;
	let start = 0;
	const parts = [];
	values.forEach((v, i) => {
		const frac = Math.max(0, v) / total;
		const end = start + frac * 2 * Math.PI;
		const x1 = cx + r * Math.cos(start);
		const y1 = cy + r * Math.sin(start);
		const x2 = cx + r * Math.cos(end);
		const y2 = cy + r * Math.sin(end);
		const large = end - start > Math.PI ? 1 : 0;
		const color = s0 && s0.ptColors && s0.ptColors[i] || s0 && s0.color || palette[i % palette.length];
		const d = frac >= 1 ? `<circle cx="${cx}" cy="${cy}" r="${r}" fill="${color}" />` : `<path d="M ${cx} ${cy} L ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2} Z" fill="${color}" />`;
		parts.push(d);
		if (el.showDataLabels && frac > 0) {
			const mid = (start + end) / 2;
			const lx = cx + (r + 12) * Math.cos(mid);
			const ly = cy + (r + 12) * Math.sin(mid);
			const catName = String(el.categories[i] || "");
			const pieLabel = catName ? `${escape(catName)} ${(frac * 100).toFixed(0)}%` : `${(frac * 100).toFixed(0)}%`;
			parts.push(`<text x="${lx}" y="${ly}" text-anchor="middle" font-size="10" fill="#000">${pieLabel}</text>`);
		}
		start = end;
	});
	return parts.join("\n");
}
function renderScatter(el, width, height, pad, palette) {
	const chartW = width - pad * 2;
	const chartH = height - pad * 2;
	const allPoints = [];
	for (const s of el.series) {
		const pts = s.points || [];
		for (const p of pts) allPoints.push(p);
	}
	let minX = Math.min(...allPoints.map((p) => p.x));
	let maxX = Math.max(...allPoints.map((p) => p.x));
	let minY = Math.min(...allPoints.map((p) => p.y));
	let maxY = Math.max(...allPoints.map((p) => p.y));
	if (!Number.isFinite(minX) || !Number.isFinite(maxX)) {
		minX = 0;
		maxX = 1;
	}
	if (!Number.isFinite(minY) || !Number.isFinite(maxY)) {
		minY = 0;
		maxY = 1;
	}
	if (minX === maxX) {
		minX -= .5;
		maxX += .5;
	}
	if (minY === maxY) {
		minY -= .5;
		maxY += .5;
	}
	const parts = [];
	parts.push(`<line x1="${pad}" y1="${height - pad}" x2="${width - pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
	parts.push(`<line x1="${pad}" y1="${pad}" x2="${pad}" y2="${height - pad}" stroke="#999" stroke-width="1" />`);
	computeTicks(minX, maxX, 4).forEach((t) => {
		const tx = pad + (t - minX) / Math.max(1e-9, maxX - minX) * chartW;
		const ty = height - pad;
		parts.push(`<line x1="${tx}" y1="${ty}" x2="${tx}" y2="${ty + 4}" stroke="#999" stroke-width="1" />`);
		parts.push(`<text x="${tx}" y="${ty + 16}" text-anchor="middle" font-size="10" fill="#666">${formatNumber(t, el.valueFormat)}</text>`);
	});
	computeTicks(minY, maxY, 4).forEach((t) => {
		const ty = height - pad - (t - minY) / Math.max(1e-9, maxY - minY) * chartH;
		parts.push(`<line x1="${pad - 4}" y1="${ty}" x2="${pad}" y2="${ty}" stroke="#999" stroke-width="1" />`);
		parts.push(`<text x="${pad - 6}" y="${ty + 3}" text-anchor="end" font-size="10" fill="#666">${formatNumber(t, el.valueFormat)}</text>`);
		parts.push(`<line x1="${pad}" y1="${ty}" x2="${width - pad}" y2="${ty}" stroke="#eee" stroke-width="1" />`);
	});
	el.series.forEach((s, si) => {
		const color = s.color || palette[si % palette.length];
		(s.points || []).forEach((p) => {
			const x = pad + (p.x - minX) / Math.max(1e-9, maxX - minX) * chartW;
			const y = height - pad - (p.y - minY) / Math.max(1e-9, maxY - minY) * chartH;
			parts.push(`<circle cx="${x}" cy="${y}" r="3" fill="${color}" />`);
			if (el.showDataLabels) {
				const fmt = s.valueFormat || el.valueFormat;
				parts.push(`<text x="${x + 5}" y="${y - 5}" font-size="10" fill="#000">${formatNumber(p.y, fmt)}</text>`);
			}
		});
	});
	return parts.join("\n");
}
function escape(str) {
	return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}
function computeTicks(min, max, count) {
	const span = max - min;
	if (span <= 0) return [min, max];
	const step = niceNum(span / count, true);
	const ticks = [];
	let v = Math.ceil(min / step) * step;
	while (v <= max + 1e-9) {
		ticks.push(Number(v.toFixed(10)));
		v += step;
	}
	return ticks;
}
function niceNum(range, round) {
	const exponent = Math.floor(Math.log10(range));
	const fraction = range / Math.pow(10, exponent);
	let niceFraction;
	if (round) if (fraction < 1.5) niceFraction = 1;
	else if (fraction < 3) niceFraction = 2;
	else if (fraction < 7) niceFraction = 5;
	else niceFraction = 10;
	else if (fraction <= 1) niceFraction = 1;
	else if (fraction <= 2) niceFraction = 2;
	else if (fraction <= 5) niceFraction = 5;
	else niceFraction = 10;
	return niceFraction * Math.pow(10, exponent);
}
function formatNumber(v, formatCode) {
	if (!formatCode) {
		if (Math.abs(v) >= 1e3) return `${Math.round(v)}`;
		if (Math.abs(v) >= 10) return v.toFixed(0);
		if (Math.abs(v) >= 1) return v.toFixed(1);
		return v.toFixed(2);
	}
	let isPercent = /%/.test(formatCode);
	let decimals = 0;
	const decMatch = formatCode.match(/\.([0#]+)/);
	if (decMatch) decimals = decMatch[1].length;
	const currencyMatch = formatCode.match(/([$€£¥])/);
	const currency = currencyMatch ? currencyMatch[1] : "";
	const useThousands = /#,##0/.test(formatCode);
	let n = v;
	if (isPercent) n = n * 100;
	let str = n.toFixed(decimals);
	if (useThousands) {
		const [int, frac] = str.split(".");
		const withSep = int.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
		str = frac !== void 0 ? `${withSep}.${frac}` : withSep;
	}
	if (currency) str = currency + str;
	if (isPercent) str = str + "%";
	return str;
}
function renderLegend(el, width, pad, palette) {
	let x = pad;
	const y = pad - 12;
	const parts = [];
	el.series.forEach((s, i) => {
		const color = s.color || palette[i % palette.length];
		const label = s.name || `Series ${i + 1}`;
		parts.push(`<rect x="${x}" y="${y}" width="10" height="10" fill="${color}" />`);
		parts.push(`<text x="${x + 14}" y="${y + 9}" font-size="10" fill="#333">${escape(label)}</text>`);
		x += 14 + label.length * 6 + 10;
	});
	return parts.join("\n");
}
//#endregion
//#region src/renderer/HtmlRenderer.ts
/**
* Converts a list of SlideElements into an HTML string with absolute positioning.
*/
var HtmlRenderer = class {
	/**
	* Renders a slide to an HTML <div> with all elements positioned accordingly.
	* @param elements List of SlideElement (text, image, shape)
	* @param options Optional width and height (in px) for the slide container.
	*  - If scaleToFit=true, width/height define the outer container size and contents are scaled from the base 960x540.
	* @returns HTML string representing the slide
	*/
	static render(elements, options = {}) {
		const dimension = (value, fallback) => typeof value === "number" && Number.isFinite(value) && value > 0 ? value : fallback;
		const baseW = dimension(options.baseWidth, 960);
		const baseH = dimension(options.baseHeight, 540);
		const targetW = dimension(options.width, baseW);
		const targetH = dimension(options.height, baseH);
		const scaleToFit = options.scaleToFit === true;
		const letterbox = scaleToFit ? options.letterbox !== false : options.letterbox === true;
		const htmlParts = elements.map((el) => {
			switch (el.type) {
				case "background": {
					const styleBg = Boolean(el.imageSrc) ? `background-image: url('${escapeAttribute(el.imageSrc)}'); background-size: cover; background-position: center; background-repeat: no-repeat;` : `background-color: ${safeColor(el.fillColor)};`;
					return `<div style="position:absolute; z-index:${Number.isFinite(el.zIndex) ? el.zIndex : -1}; left:0; top:0; width:${baseW}px; height:${baseH}px; ${styleBg}"></div>`;
				}
				case "text": return renderTextElement(el);
				case "image": return renderImageElement(el);
				case "shape": return renderShapeElement(el, { scaleStrokes: scaleToFit });
				case "table": return renderTableElement(el);
				case "chart": return renderChartElement(el);
				default:
					if (typeof console !== "undefined" && console.warn) console.warn(`[pptx-to-html] Unsupported element type: ${el?.type}`);
					return "";
			}
		});
		if (scaleToFit) if (letterbox) {
			const s = Math.min(targetW / baseW, targetH / baseH);
			return `<div class="slide-container" style="position: relative; width: ${targetW}px; height: ${targetH}px; overflow: hidden; background-color: #000;">
            <div class="slide" style="position: absolute; left: ${(targetW - baseW * s) / 2}px; top: ${(targetH - baseH * s) / 2}px; width: ${baseW}px; height: ${baseH}px; transform: scale(${s}); transform-origin: top left; background-color: #fff;">
              ${htmlParts.join("\n")}
            </div>
          </div>`;
		} else return `<div class="slide-container" style="position: relative; width: ${targetW}px; height: ${targetH}px; overflow: hidden;">
            <div class="slide" style="position: absolute; left: 0; top: 0; width: ${baseW}px; height: ${baseH}px; transform: scale(${targetW / baseW}, ${targetH / baseH}); transform-origin: top left; background-color: #fff;">
              ${htmlParts.join("\n")}
            </div>
          </div>`;
		return `<div class="slide" style="position: relative; width: ${targetW}px; height: ${targetH}px; overflow: hidden; background-color: #fff;">
        ${htmlParts.join("\n")}
      </div>`;
	}
};
//#endregion
//#region src/index.ts
/**
* Converts a PPTX file buffer into an array of HTML slides.
* @param buffer ArrayBuffer representing the .pptx file.
* @param config Optional rendering configuration object.
* @param config.width Target container width in pixels (defaults to 960 when not provided).
* @param config.height Target container height in pixels (defaults to 540 when not provided).
* @param config.scaleToFit When true, scales the slide content to fit the container size.
* @param config.letterbox When scaling, use black bars to preserve aspect ratio (defaults to true when scaleToFit is true).
* @returns Array of HTML strings, each representing one slide.
*/
async function pptxToHtml(buffer, config) {
	if (config?.domParserFactory) XmlHelper.setDomParser(config.domParserFactory);
	const reader = new PptxReader();
	const slides = await reader.load(buffer);
	const base = await reader.getBaseSizePx();
	const opts = {
		...config || {},
		baseWidth: base.width,
		baseHeight: base.height
	};
	return slides.map((slideElements) => HtmlRenderer.render(slideElements, opts));
}
//#endregion
export { pptxToHtml };

//# sourceMappingURL=index.js.map