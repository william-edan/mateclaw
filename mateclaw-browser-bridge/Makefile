# MateClaw Browser Bridge - Native Host build & test targets.

.PHONY: build bundle exe test dev clean

build:
	pnpm build

# Single-file JS bundle (no executable).
bundle:
	pnpm build:bundle

# Single-file Windows x64 executable → dist/cmd/bridge.exe (run on Windows x64).
exe:
	pnpm build:exe

test:
	pnpm test

dev:
	pnpm dev

clean:
	rm -rf dist/
