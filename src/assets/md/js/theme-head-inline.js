/* SsvulExtWebCreator · https://github.com/Endingratitan/SsvulExtWebCreator | MPL-2.0: redistributed with this notice and LICENSE/NOTICE | Copyright (c) 2026 Endingratitan */
/* 首帧恢复：主题（ssvul-theme）与访客调色板（ssvul-palette，CSS 变量覆盖表）。
   v0.3.0 迁移：旧版 palette-picker 用 setPalette 写下的变量覆盖会压过所有主题（内联 style 优先级最高），
   故以 ssvul-palette-ver 标记做一次性清除（仅首次升级时清一次；新版 setPalette 会写入该标记）。 */
(function(){var VER='2';try{if(localStorage.getItem('ssvul-palette-ver')!==VER){localStorage.removeItem('ssvul-palette');localStorage.setItem('ssvul-palette-ver',VER);}}catch(e){}var t=localStorage.getItem('ssvul-theme');if(t)document.documentElement.setAttribute('data-theme',t);var p=localStorage.getItem('ssvul-palette');if(p){try{p=JSON.parse(p);for(var k in p)document.documentElement.style.setProperty(k,p[k]);}catch(e){}}})();
