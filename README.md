# 🟩🌧️💊 Digital Rain

A plugin for Nagram XF. Movie style digital rain as the background of every chat.

## What you get

* Mirrored half width katakana, digits and a few symbols, as in the film.
* Columns falling at different speeds, each led by a bright white glyph with a soft glow.
* Trails that fade from bright to dark, with glyphs flickering inside them.
* A black background behind your bubbles.

## Settings

* Digital Rain on or off.
* Color hex, film green `#00FF41` by default.
* Color slider that sweeps the hue and writes the hex for you.
* Rotate through colors, and the color rotation speed.
* Rain speed, from 0.2x to 3x.
* Particle count, from 10 to 300 falling streams.

## Performance

All drawing lives in a small embedded Java view, so no Python runs per frame. It ticks on vsync only while its chat is on screen, redraws at most 24 times a second, stops completely when the chat is covered or the screen is off, and allocates nothing per frame. Every glyph on screen goes out in one `drawVertices` call from a glyph atlas built once.

On a Pixel 6 Pro in a busy forum topic, scrolling stayed the same with it on and off (median frame 29 ms on, 26 ms off; janky frames 23% on, 41% off), and idle CPU stayed within the normal swing of the app.

## How it differs from Custom Snow's Matrix mode

Custom Snow scatters single green characters as particles over the wallpaper. Digital Rain replaces the wallpaper with real falling columns, leading glyphs and fading trails.

## Install

1. Use the plugin build of Nagram XF and turn on the plugin engine (Settings, N Settings, Plugins).
2. Download `digital_rain.plugin` from the latest release.
3. Send it to your Saved Messages, tap it and choose install.

The Java source is in `java/`. The plugin carries the compiled class inline.

## License

MIT

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=firestarter1996/xf-digital-rain&type=Date)](https://star-history.com/#firestarter1996/xf-digital-rain&Date)
