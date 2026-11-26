/**
 * Source text of the editing logic of the bridge of the HTML preview: it runs INSIDE the sandboxed iframe, on the
 * document of the page, so that the CSS of the page applies to what is inserted.
 *
 * It is kept as source text, like the serializer of `exchange-html-srcdoc`, so that the very same code runs in the
 * iframe (`var E=(function(){...})()`) and in the unit tests (`new Function(EXCHANGE_EDIT_SOURCE)()`, on a jsdom
 * Document): no second copy that could drift apart. Every function takes the `doc` it works on.
 *
 * RULES FOR THIS SOURCE: ES5-style, semicolons everywhere, NO `//` comments (it is embedded in a one-line script),
 * no backtick and no dollar-brace, no closing script tag.
 *
 * What it offers (`E`):
 * - `selectionHtml(doc, range, cap)` / `selectionContext(range)`: what the agent is told about a selection (the markup
 *   of the selected passage, and the chain of elements around it, up to body).
 * - `apply(doc, range, html, text)`: replaces the passage of `range` with an agent proposal.
 *   - plain text, or an HTML fragment with the same sequence of tags as the passage (a block wrapper such as the
 *     `<p>` around it is ignored): `textEdit`, a STYLE-PRESERVING
 *     edit. A word-level diff of the old text against the new one is applied node by node on the text nodes of the
 *     range, so each text node keeps its parent elements (tags, classes, inline styles). Elements are never replaced.
 *     A changed word takes the place, hence the style, of the word it replaces; an insertion goes into the text node of
 *     the preceding kept word, or of the following one at the start; emptied text nodes are removed.
 *   - an HTML fragment whose tags differ (the agent changed the markup): `insertFragment`. The fragment is parsed
 *     inertly in a `<template>` and sanitized first (`parse`), then it replaces the contents of the range, at the
 *     caret position, so an inline fragment inherits the surrounding inline styling. Partially covered elements left
 *     empty are removed, except when they carry a class, a style or an id (a fully covered element is removed by
 *     `deleteContents` itself, empty or not).
 * - `parse(doc, html)`: the minimal allow-list sanitiser. Removes script, iframe, object, embed and the like, every
 *   `on*` attribute, `srcdoc`, and `javascript:` / `data:` / `vbscript:` URLs of href, src, action and the like.
 *   The sandbox, not this function, is what makes the page safe: the proposal comes from an agent, and is only ever
 *   applied on an explicit click of the user.
 */
export const EXCHANGE_EDIT_SOURCE = String.raw`
var SKIP="script,iframe,object,embed,frame,frameset,applet,link,meta,base";
var URL_ATTR=/^(?:href|src|action|formaction|xlink:href|poster|background|data|ping|srcset)$/i;
var BAD_URL=/^(?:javascript|data|vbscript):/i;
var BAD_STYLE=/javascript:|expression\s*\(|behavior\s*:/i;
function tokenize(s){return s.match(/\s+|\S+/g)||[];}
function key(t){return /^\s/.test(t)?" ":t;}
function diffOps(a,b){
  var x=tokenize(a),y=tokenize(b),ops=[],n=x.length,m=y.length,i,j;
  function push(t,s){var l=ops[ops.length-1];if(l&&l.t===t){l.s+=s;}else{ops.push({t:t,s:s});}}
  if(n*m>4000000){if(a){push("del",a);}if(b){push("ins",b);}return ops;}
  var L=[];
  for(i=0;i<=n;i++){L.push(new Array(m+1).fill(0));}
  for(i=n-1;i>=0;i--){for(j=m-1;j>=0;j--){L[i][j]=key(x[i])===key(y[j])?L[i+1][j+1]+1:Math.max(L[i+1][j],L[i][j+1]);}}
  i=0;j=0;
  while(i<n&&j<m){
    if(key(x[i])===key(y[j])){push("eq",x[i]);i++;j++;}
    else if(L[i+1][j]>=L[i][j+1]){push("del",x[i]);i++;}
    else{push("ins",y[j]);j++;}
  }
  while(i<n){push("del",x[i]);i++;}
  while(j<m){push("ins",y[j]);j++;}
  return ops;
}
function pieces(doc,range){
  var root=range.commonAncestorContainer,out=[],all=0,n;
  function add(t){
    var from=t===range.startContainer?range.startOffset:0;
    var to=t===range.endContainer?range.endOffset:t.data.length;
    if(to>from){out.push({n:t,from:from,to:to,gs:all});all+=to-from;}
  }
  if(root.nodeType===3){add(root);return out;}
  var w=doc.createTreeWalker(root,4);
  while((n=w.nextNode())){if(range.intersectsNode(n)){add(n);}}
  return out;
}
function textEdit(doc,range,text){
  var ps=pieces(doc,range),k,old="";
  if(!ps.length){return false;}
  for(k=0;k<ps.length;k++){old+=ps[k].n.data.slice(ps[k].from,ps[k].to);}
  var lead=/^\s*/.exec(old)[0],trail=/\s*$/.exec(old.slice(lead.length))[0];
  var ops=diffOps(old,lead+text.replace(/^\s+|\s+$/g,"")+trail);
  var pos=0,g=null,changes=[],o;
  for(k=0;k<ops.length;k++){
    o=ops[k];
    if(o.t==="eq"){if(g){changes.push(g);g=null;}pos+=o.s.length;}
    else{
      if(!g){g={at:pos,del:0,ins:""};}
      if(o.t==="del"){g.del+=o.s.length;pos+=o.s.length;}else{g.ins+=o.s;}
    }
  }
  if(g){changes.push(g);}
  var edits=ps.map(function(){return [];});
  function pieceAt(p){for(var q=ps.length-1;q>=0;q--){if(ps[q].gs<=p){return q;}}return 0;}
  changes.forEach(function(c){
    var first=true,q,s,e;
    if(c.del>0){
      for(q=0;q<ps.length;q++){
        s=Math.max(c.at,ps[q].gs);
        e=Math.min(c.at+c.del,ps[q].gs+ps[q].to-ps[q].from);
        if(e>s){edits[q].push({at:ps[q].from+s-ps[q].gs,del:e-s,ins:first?c.ins:""});first=false;}
      }
    }else if(c.ins){
      q=c.at>0?pieceAt(c.at-1):0;
      edits[q].push({at:c.at>0?ps[q].from+c.at-ps[q].gs:ps[q].from,del:0,ins:c.ins});
    }
  });
  ps.forEach(function(p,q){
    edits[q].sort(function(a,b){return b.at-a.at;});
    edits[q].forEach(function(e){p.n.replaceData(e.at,e.del,e.ins);});
  });
  ps.forEach(function(p){if(!p.n.data&&p.n.parentNode){p.n.parentNode.removeChild(p.n);}});
  return true;
}
function sig(root){
  var l=root.querySelectorAll("*"),o=[],i;
  for(i=0;i<l.length;i++){o.push(l[i].tagName.toLowerCase());}
  return o.join(",");
}
function clean(f){
  var bad=f.querySelectorAll(SKIP),i,j,el,a,v,nm;
  for(i=0;i<bad.length;i++){if(bad[i].parentNode){bad[i].parentNode.removeChild(bad[i]);}}
  var all=f.querySelectorAll("*");
  for(i=0;i<all.length;i++){
    el=all[i];
    var attrs=Array.prototype.slice.call(el.attributes);
    for(j=0;j<attrs.length;j++){
      a=attrs[j];
      nm=a.name.toLowerCase();
      v=String(a.value).replace(/[\x00-\x20\x7f-\x9f]+/g,"");
      if(nm.slice(0,2)==="on"||nm==="srcdoc"||(URL_ATTR.test(nm)&&BAD_URL.test(v))||(nm==="style"&&BAD_STYLE.test(v))){el.removeAttribute(a.name);}
    }
  }
}
function parse(doc,html){
  var t=doc.createElement("template");
  t.innerHTML=html;
  clean(t.content);
  return doc.importNode(t.content,true);
}
function keeps(n){return n.hasAttribute("class")||n.hasAttribute("style")||n.hasAttribute("id");}
function below(n,stop){var a=[];while(n&&n!==stop){a.push(n);n=n.parentNode;}return a;}
function insertFragment(doc,range,frag){
  var cac=range.commonAncestorContainer;
  var cand=below(range.startContainer,cac).concat(below(range.endContainer,cac));
  if(cac.nodeType===3){cand.push(cac);}
  range.deleteContents();
  cand.forEach(function(n){
    var p=n.parentNode;
    if(!p){return;}
    if(n.nodeType===3){if(!n.data){p.removeChild(n);}}
    else if(n.nodeType===1&&!n.firstChild&&!keeps(n)){p.removeChild(n);}
  });
  range.insertNode(frag);
  return true;
}
function sameTags(f,clone){
  var s=sig(clone),node=f,k,c,e;
  if(sig(f)===s){return true;}
  for(k=0;k<4;k++){
    e=null;
    for(c=node.firstChild;c;c=c.nextSibling){
      if(c.nodeType===1){if(e){return false;}e=c;}
      else if(c.nodeType===3&&/\S/.test(c.data)){return false;}
    }
    if(!e||!/^(?:p|div|li|h[1-6]|td|th|dd|dt|blockquote|section|article|figcaption|caption)$/i.test(e.tagName)){return false;}
    if(sig(e)===s){return true;}
    node=e;
  }
  return false;
}
function apply(doc,range,html,text){
  if(typeof html==="string"){
    var f=parse(doc,html);
    if(!f.querySelector("*")){return textEdit(doc,range,f.textContent||"");}
    if(sameTags(f,range.cloneContents())){return textEdit(doc,range,f.textContent||"");}
    return insertFragment(doc,range,f);
  }
  if(typeof text==="string"){return textEdit(doc,range,text);}
  return false;
}
function selectionHtml(doc,range,cap){
  var inert=doc.implementation.createHTMLDocument(""),d=inert.createElement("div"),i;
  d.appendChild(inert.importNode(range.cloneContents(),true));
  var m=d.querySelectorAll("mark[data-agentos-selection]");
  for(i=0;i<m.length;i++){var p=m[i].parentNode;while(m[i].firstChild){p.insertBefore(m[i].firstChild,m[i]);}p.removeChild(m[i]);}
  var h=d.innerHTML;
  return h.length>cap?h.slice(0,cap):h;
}
function selectionContext(range){
  var n=range.commonAncestorContainer,o=[];
  if(n.nodeType!==1){n=n.parentNode;}
  while(n&&n.nodeType===1&&o.length<12){
    var e={tag:n.tagName.toLowerCase()},c=n.getAttribute("class"),s=n.getAttribute("style");
    if(c){e["class"]=c.slice(0,200);}
    if(s){e.style=s.slice(0,300);}
    o.push(e);
    if(e.tag==="body"||e.tag==="html"){break;}
    n=n.parentNode;
  }
  return o;
}
return {diffOps:diffOps,textEdit:textEdit,sig:sig,sameTags:sameTags,parse:parse,insertFragment:insertFragment,apply:apply,selectionHtml:selectionHtml,selectionContext:selectionContext};
`
