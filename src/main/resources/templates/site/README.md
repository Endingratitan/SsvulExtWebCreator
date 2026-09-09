# My Site

Generated with SsvulExtWebCreator {{VERSION}} (https://github.com/Endingratitan/SsvulExtWebCreator).

## Build

- Local: `java -jar ssvul-<version>.jar build` (output to `output/`)
- CI: the generated workflow deploys `output/` to Cloudflare Pages / GitHub Pages

## Structure

- `Environment.config` — site config (cname/bucket/offline/...)
- `divs/` — your div components (template.html + js/css, `.global`/`.adds`/`.extends` markers)
- `pages/` — pages (json / bare md / raw folders)
- `data/` — data files; `outer/` — bucket mirror for offline mode; `favicon/` — local favicon
