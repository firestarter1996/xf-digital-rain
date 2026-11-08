# 🟩🌧️💊 Digital Rain

A plugin for Nagram XF. Movie style digital rain as the background of every chat and of the main screen header.

## What you get

* Mirrored half width katakana, digits and a few symbols, as in the film.
* Columns falling at different speeds, each led by a bright white glyph with a soft glow.
* Trails that fade from bright to dark, with glyphs flickering inside them.
* A black background behind your bubbles.
* The same rain behind the main screen header, a little dimmer so the title and icons stay easy to read.

## Settings

* Digital Rain on or off.
* Color hex, film green `#00FF41` by default.
* Color slider that sweeps the hue and writes the hex for you, with a live swatch and the hex shown in the chosen color.
* Rotate through colors, and the color rotation speed.
* Rain speed, from 0.2x to 3x.
* Particle count, from 10 to 300 falling streams.
* Reset to default puts every setting back at once.

## Performance

All drawing lives in a small embedded Java view, so no Python runs per frame. It ticks on vsync only while its chat is on screen, redraws at most 24 times a second, stops completely when the chat is covered or the screen is off, and allocates nothing per frame. Every glyph on screen goes out in one `drawVertices` call from a glyph atlas built once.

On a Pixel 6 Pro in a busy forum topic, scrolling stayed the same with it on and off (median frame 29 ms on, 26 ms off; janky frames 23% on, 41% off), and idle CPU stayed within the normal swing of the app.

The header rain redraws at most 16 times a second with fewer streams. On the 6 Pro the chat list screen used about 8% of one CPU core more with it on, because the app's glass effects redraw with every frame. It stops the moment you leave the chat list.

## What's new in 1.1.1 (11-08-2026)

Fixes Nagram XF force closing while a chat was being drawn right after the app opened. The hook that keeps holiday snow off the rain was installed from the plugin loading thread while the UI thread drew snow every frame; a frame that met the half installed hook crashed in XposedBridge with a null original method. The rain core still loads off the UI thread, and every hook is now installed on the UI thread, between frames.

## How it differs from Custom Snow's Matrix mode

Custom Snow scatters single green characters as particles over the header. Digital Rain draws real falling columns, leading glyphs and fading trails behind your chats and the header, so it replaces Custom Snow. Where the rain is on, holiday snow is not drawn over it.

## Install

1. Use the plugin build of Nagram XF and turn on the plugin engine (Settings, N Settings, Plugins).
2. Download `digital_rain.plugin` from the latest release.
3. Send it to your Saved Messages, tap it and choose install.

The Java source is in `java/`. The plugin carries the compiled class inline.

## License

MIT

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=firestarter1996/xf-digital-rain&type=Date)](https://star-history.com/#firestarter1996/xf-digital-rain&Date)
