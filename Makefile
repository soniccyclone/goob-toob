IMAGE   := localhost/goob-toob:latest
STORAGE ?= $(CURDIR)/storage
PASS    ?= goob-dev-password
PORT    ?= 8080

MOUNTS := -v $(STORAGE):/data:z -v $(CURDIR):/app:z -w /app
ENV    := -e STORAGE_DIR=/data -e ADMIN_PASSWORD=$(PASS) -e PORT=8080

.PHONY: help image dev run test compile repl clip funnel funnel-stop

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

# tailscale funnel fronts the port itself (implies serve -> 443); friends
# need no tailscale, just the https://<machine>.<tailnet>.ts.net URL it prints.
funnel: ## expose the running app (PORT=8080) to the internet via Tailscale Funnel
	tailscale funnel --bg $(PORT)

funnel-stop: ## stop the Tailscale Funnel (tailnet-only serve stays up)
	tailscale funnel --stop

# cloudflared quick tunnel: no account, ephemeral https://<random>.trycloudflare.com
# URL printed in your terminal; runs in the foreground, Ctrl-C to stop.
tunnel: ## temporary public URL via Cloudflare quick tunnel (Ctrl-C stops it)
	cloudflared tunnel --url http://localhost:$(PORT)
