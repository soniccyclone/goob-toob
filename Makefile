IMAGE   := localhost/goob-toob:latest
STORAGE ?= $(CURDIR)/storage
PASS    ?= goob-dev-password
PORT    ?= 8080

MOUNTS := -v $(STORAGE):/data:z -v $(CURDIR):/app:z -w /app
ENV    := -e STORAGE_DIR=/data -e ADMIN_PASSWORD=$(PASS) -e PORT=8080

uberjar: ## build server uberjar (CLJS included) -> target/goob-toob.jar
	mkdir -p $(STORAGE)
	podman run --rm $(MOUNTS) $(ENV) $(IMAGE) sh -c "npx shadow-cljs release client && clojure -T:build uberjar"

vendor: ## download cloudflared + ffmpeg + JRE into vendor/ (desktop bundle)
	sh scripts/fetch-vendor.sh all

vendor-linux: ## vendor just the linux binaries
	sh scripts/fetch-vendor.sh linux

desktop: ## package the Electron desktop app (dir build) -> desktop/dist/
	$(MAKE) uberjar
	$(MAKE) vendor
	cd desktop && npm install --no-audit --no-fund && npx electron-builder --dir

.PHONY: help image dev run test compile repl clip tunnel tunnel-stop uberjar vendor vendor-linux desktop

help:
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[32m%-12s\033[0m %s\n", $$1, $$2}'

image: ## build the podman image (dev + deploy)
	podman build -f container/Containerfile -t $(IMAGE) .

dev: ## interactive container with repo mounted (shadow watch, REPL)
	mkdir -p $(STORAGE)
	podman run --rm -it -p $(PORT):8080 $(MOUNTS) $(ENV) $(IMAGE) bash container/dev.sh

run: ## run the app (PORT=8080 default, override with make run PORT=...)
	mkdir -p $(STORAGE)
	podman run --rm -p $(PORT):8080 -v $(STORAGE):/data:z $(ENV) $(IMAGE)

test: ## run the CLJ test suite in the container
	podman run --rm $(MOUNTS) $(ENV) $(IMAGE) clojure -M:test

compile: ## shadow-cljs release build of the client
	podman run --rm $(MOUNTS) $(ENV) $(IMAGE) npx shadow-cljs release client

repl: ## nREPL in the container
	podman run --rm -it $(MOUNTS) $(ENV) $(IMAGE) clojure -M:dev:test:repl

# 20s test clip (lavfi sources) for smoke-testing the transcode pipeline
clip: ## generate storage/testclip.mp4 (needs host ffmpeg)
	mkdir -p $(STORAGE)
	ffmpeg -y -hide_banner -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=20" \
	  -f lavfi -i "sine=frequency=440:duration=20" \
	  -c:v libopenh264 -c:a aac -shortest $(STORAGE)/testclip.mp4

# Cloudflare quick tunnel: no account, no open ports (dials out), ephemeral
# https://<random>.trycloudflare.com URL. Preferred exposure while the
# tailscale funnel DNS bug (tailscale/tailscale#21698) is unresolved.
tunnel: ## start the public Cloudflare quick tunnel (background, prints URL)
	@if [ -f .cloudflared.pid ] && kill -0 $$(cat .cloudflared.pid) 2>/dev/null; then \
	  echo "tunnel already running (pid $$(cat .cloudflared.pid))"; \
	else \
	  nohup cloudflared tunnel --url http://localhost:$(PORT) > tunnel.log 2>&1 & \
	  echo $$! > .cloudflared.pid; \
	  echo "starting tunnel..."; sleep 6; \
	  grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" tunnel.log | head -1 || cat tunnel.log; \
	fi

tunnel-stop: ## stop the Cloudflare quick tunnel
	@if [ -f .cloudflared.pid ]; then kill $$(cat .cloudflared.pid) 2>/dev/null; rm -f .cloudflared.pid; echo "tunnel stopped"; else echo "no tunnel running"; fi
