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

All drawing lives in a small embedded Java view, so no Python runs per frame. It ticks on vsync only while its chat is on screen and stops completely when the chat is covered or the screen is off. Every position comes from a clock driven by the vsync timestamps, so the rain moves at the same speed at 60, 90 or 120 Hz and simply pauses while it is hidden. Nothing is allocated per frame, and every glyph on screen goes out in one `drawVertices` call from a glyph atlas built once.

The chat rain draws on every vsync. When the app starts missing frames (a heavy scroll, a busy chat) or the rain's own draw time grows, it skips every other vsync, and up to three in four, until frames are on time again; motion stays smooth because positions follow the clock, not the frame count. The header rain moves at most 60 times a second with fewer streams, because the chat list's glass effects redraw with it, and it stops the moment you leave the chat list.

## What's new in 1.2.0 (11-10-2026)

Smooth motion. Earlier versions redrew at most 24 times a second (16 in the header), and the bright head jumped a whole character cell each time it reached a new row, so the rain advanced in visible steps. Now:

* The head glyph and its glow glide down by sub pixel steps on every frame.
* The newest trail glyph comes out from under the head instead of popping in.
* Trails fade all the way to nothing, so the last glyph no longer blinks out.
* The flicker of glyphs inside the trails runs at the same rate whatever the refresh rate.
* Color rotation follows the same clock, so it never stutters.

The look is the same: glyphs still sit in their columns and change in place, as in the film.

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
