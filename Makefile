IMAGE   := localhost/goob-toob:latest
STORAGE ?= $(CURDIR)/storage
PASS    ?= goob-dev-password
PORT    ?= 8080

MOUNTS := -v $(STORAGE):/data -v $(CURDIR):/app -w /app
ENV    := -e STORAGE_DIR=/data -e ADMIN_PASSWORD=$(PASS) -e PORT=8080

.PHONY: image dev run test compile repl clip

image:
	podman build -f container/Containerfile -t $(IMAGE) .

dev:
	mkdir -p $(STORAGE)
	podman run --rm -it -p $(PORT):8080 $(MOUNTS) $(ENV) $(IMAGE) bash container/dev.sh

run:
	mkdir -p $(STORAGE)
	podman run --rm -p $(PORT):8080 -v $(STORAGE):/data $(ENV) $(IMAGE)

test:
	podman run --rm $(MOUNTS) $(ENV) $(IMAGE) clojure -M:test

compile:
	podman run --rm $(MOUNTS) $(ENV) $(IMAGE) npx shadow-cljs release client

repl:
	podman run --rm -it $(MOUNTS) $(ENV) $(IMAGE) clojure -M:dev:test:repl

# 20s test clip (lavfi sources) for smoke-testing the transcode pipeline
clip:
	mkdir -p $(STORAGE)
	ffmpeg -y -hide_banner -loglevel error -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=20" \
	  -f lavfi -i "sine=frequency=440:duration=20" \
	  -c:v libopenh264 -c:a aac -shortest $(STORAGE)/testclip.mp4
