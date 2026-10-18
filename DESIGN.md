# Summit design language: NIGHT CITY TERMINAL

*Shares MusicSync's visual language (`~/Documents/MusicSync/static/style.css`) so the two apps read as a family.*

Adopted 2026-10-17, replacing the earlier "Neon Terminal" pixel spec. Implemented in `src/summit/web/shared.css` (components)
and `src/summit/web/map.js` (map marks). Pages add layout only.

## Principles
1. **Night city terminal.** Neon on near-black, monospace everywhere, chamfered frames, static CRT scanlines.
2. **Colour has one job.** Each colour role means one thing everywhere (below).
3. **Still at rest, alive when playing.** Ambient effects never move or change brightness. Motion means something is happening.
4. **Self-explanatory.** No legends or always-on instructions: labels on the map name the clusters. All hints, tips and explainers form a **help layer**, hidden by default and toggled with **Ctrl+Alt+Shift+D** (remembered per machine).
5. **Marks beat data.** Anything drawn on top of the map (stages, rings, the playing mark) contrasts with the data under it. It is never filled with a data colour.

## Colour
| Role | Value | Used for |
|---|---|---|
| Night | `#05040a` | App background (plus faint 40px cyan grid and magenta/cyan haze on maps) |
| Surface | `rgba(12,10,24,.86)` / panel `#0a0816` | Frames, docked panels |
| Ink | `#d8f8ff` / muted `#6f8ba3` | Text |
| Rule | `rgba(0,240,255,.22)` / soft `.1` | Borders, dividers, row separators |
| Control cyan | `#00f0ff` | Buttons, toggles, sliders, inputs (left bar), active tab, section titles |
| Primary yellow | `#fcee0a` | The main action on a screen, the play button, **selection** (checked rows, selected tag/stage, lasso), caret |
| Confirmed green | `#05ffa1` | Confirmed tags, tag-score scale, equaliser, track facts |
| Danger magenta | `#ff2a6d` | Delete / remove, header and player edges, frame corner, `//` and `›` prefixes, the ♥ like (on any song widget) |
| Focus white | `#ffffff` | Highlight rings, playing mark, route, corridor |
| Data palette | 16 neon hues | **Only** cluster data (dots, timeline blocks, row bars, stage fills). Each sub-cluster has its own hue (siblings never share one); a family's label uses its largest sub-cluster's hue |

## Type
| Term | Font | Use |
|---|---|---|
| Display | Orbitron 800–900, uppercase, wide tracking | Brand, panel titles, big stat numbers, stage numbers, map cluster labels |
| Mono | Share Tech Mono | Everything else: body, buttons (uppercase, 0.14em), tables, inputs |
| Japanese | DotGothic16 (display) / M PLUS 1 Code (mono) as glyph fallbacks; tracking tightened to 0.04em | When the interface is switched to 日本語 (header toggle). Library data stays untranslated |

## Form
| Term | Rule |
|---|---|
| Chamfer | Cut corners via clip-path: 9px on buttons and segmented toggles, 14px on frames and cards |
| Frame | Surface + 1px rule + cyan neon line along 38% of the top edge + magenta corner triangle at the cut |
| Header / player edge | 1px magenta line with magenta glow |
| Input | Dark fill, 1px rule, 3px cyan left bar, yellow caret; focus = cyan ring + glow |
| Checkbox | Rotated diamond outline; checked = yellow fill + glow |
| Dot | Rotated diamond in its colour with glow (lists, player) |
| Stat | Dark box, 3px magenta left bar (yellow for warnings), Orbitron number over an uppercase label |
| LED meter | Segmented bar, 6px on / 2px off, glowing |
| Chip (tag) | 1px border in role colour, 8% fill; suggested = dashed muted |
| Prefixes | `// ` (magenta) before section titles, hints and toasts; `› ` before kickers |

## Interaction
| Term | Rule |
|---|---|
| Hover (button) | Fills with its colour, dark text, glow |
| Selection | Yellow inset bar + 6% yellow wash on rows; yellow lasso outline |
| Playing | White inset bar on the row and ▶ before the title; white outline + moving stripes on the timeline block |
| Fade | Out-of-focus map dots go to 20% opacity and lose their glow |

## Map marks
| Term | Rule |
|---|---|
| Dot | 6px diamond in the cluster colour + 13px faint diamond halo (glow) |
| Ring | White open diamond (highlight / picked) |
| Playing mark | Large white diamond, translucent fill, breathes while playing |
| Stage marker | Black tile, white Orbitron numeral, white edge (yellow when selected), framed by a ring in its cluster colour; drawn above cluster labels |
| Route / corridor | 2.5px white line over a faint white wash / 7% white fill |
| Cluster label | Orbitron, boxed with a 1px border in its colour. Zoomed out: family names (10px). Zoomed in past half the width: sub-cluster names (8px) inside split families |

## Motion
| Tier | When | Effects |
|---|---|---|
| Static | Always | Scanlines + vignette, grid, edges |
| Live | Only while audio plays | Equaliser, play-button pulse, timeline stripes, breathing playing mark |
| Event | One-shot | Toast, lasso marching ants while dragging, button hover |
| Signature | Rare ambient exception | Glitch on `h1` titles (~6s), as in MusicSync |
