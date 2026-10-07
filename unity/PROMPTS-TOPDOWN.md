# Промпты для вида сверху (решение владельца 07.10: «как Grim Soul», графику генерируем)

Здесь только пробная партия: улица у городских ворот и порт. По ней видно, держит ли генератор единый стиль и ракурс. Если держит, распишу остальные зоны: лес, пещеры, кладбище, болото, Египет, замок.

## Как устроен вид и что из этого следует для картинок

Место — площадка, на которую смотрят сверху и чуть сбоку, как в Grim Soul. Собирается она из трёх слоёв, и для каждого свой тип картинок.

1. **Земля** — бесшовные текстуры, снятые строго сверху. Движок замостит ими площадку и сам смешает границы (булыжник переходит в траву), поэтому отдельные плитки-переходы не нужны.
2. **Обстановка** — дома, деревья, бочки, камни. Каждый предмет отдельной картинкой, с ракурсом «сверху под углом». Движок расставит их сам и подложит тени.
3. **Фигуры** — герой, монстры, жители. Тоже по одной картинке, с тем же ракурсом. Шаги и удары изображает движок: покачивание, наклон, рывок, отражение влево и вправо. Поэтому кадры анимации рисовать не нужно, достаточно одной позы.

**Почему нельзя просто «нарисуй локацию сверху целиком».** Такую картинку не разобрать на части: по ней нельзя ходить за домами, нельзя расставить монстров, а на 1400 мест не наберёшь 1400 картин. Площадки движок будет собирать из одних и тех же деталей по типу места.

## Правила для всех картинок (иначе детали не сложатся вместе)

1. **Один ракурс.** Обстановка и фигуры — изометрия, как у пробного дома (07.10): угол предмета смотрит на зрителя, видны две стены и крыша. Земля — строго сверху, без перспективы.
2. **Один свет** — сверху слева. Этот кусок есть в каждом промпте, не удаляйте его.
3. **Без тени на земле.** Тени движок рисует сам. Если генератор их всё-таки рисует, ничего страшного, вырежу.
4. **Фон.** Обстановке и фигурам нужен ровный светло-серый фон (#B0B0B0), его я вырежу. Если генератор умеет прозрачный фон (ChatGPT / gpt-image умеет), просите прозрачный, так ещё лучше.
5. **Предмет целиком.** Ничего не обрезано краем кадра, вокруг пустое поле.
6. **Размер** — квадрат 2048×2048. Если больше 1024 не выходит, тоже годится.
7. **Ориентир стиля.** Если генератор принимает картинку-образец, дайте ему одну из нынешних картин мест: в Midjourney это `--sref <ссылка>`, в ChatGPT — просто приложить картинку. Лучше всего подойдут «Портовый район» или «Городская улица». С образцом стиль новой графики совпадёт с остальной игрой.

## Готовые куски промпта

Промпты на английском: генераторы понимают его лучше.

**[STYLE]** — добавлять к каждому промпту:
```
dark fantasy video game asset, hand-painted digital painting, muted earthy palette with warm torchlight accents, rich surface texture detail, soft moody light from the upper left, Grim Soul and Diablo II art style, for a top-down action RPG
```

**[GROUND]** — для текстур земли:
```
seamless tileable texture, viewed perfectly straight down from directly above, orthographic top view, flat even lighting, no shadows, no perspective, no horizon, no objects, no characters, the pattern fills the whole frame edge to edge, square 1:1
```

**[OBJECT]** — для обстановки:
```
single isolated game object, classic isometric view from above, one corner of the object pointing toward the viewer so two sides and the top are visible, true 2:1 isometric orthographic projection with no vanishing points, all vertical edges perfectly vertical, parallel edges stay parallel, both visible walls at the same 30 degree angle, simple rectangular footprint sitting flat on the ground, the whole object visible with empty margin around it, centered, light from the upper left, no cast shadow, no ground under it, plain flat light grey background #B0B0B0, no text, no frame, square 1:1
```

**[FIGURE]** — для героя, монстров и жителей:
```
single character, full body, classic isometric view from above (same camera as an isometric RPG, true 2:1 isometric orthographic projection, no perspective distortion), standing in a calm idle pose, body turned three-quarters to the lower right, the whole figure visible with empty margin around it, centered, light from the upper left, no cast shadow, no ground under it, plain flat light grey background #B0B0B0, no text, no frame, square 1:1
```

**Чего избегать** (если у генератора есть поле negative prompt):
```
horizon, sky, landscape, scene, multiple objects, cropped, cut off, cast shadow, ground plane, isometric grid, text, watermark, signature, frame, border, photo, 3d render, low poly, cartoon, anime, pixel art
```

**Midjourney:** в конце дописать `--ar 1:1 --style raw --sref <ссылка на картину места>`. Для земли ещё `--tile`: с ним текстура получается бесшовной. Без `--tile` тоже можно, швы уберу сам.

Полный промпт собирается так: **описание из таблицы + [GROUND], [OBJECT] или [FIGURE] + [STYLE]**. Например, бочка:
```
an old oak barrel with rusty iron hoops, slightly battered, single isolated game object, classic isometric view from above, one corner of the object pointing toward the viewer so two sides and the top are visible, true 2:1 isometric orthographic projection with no vanishing points, all vertical edges perfectly vertical, parallel edges stay parallel, both visible walls at the same 30 degree angle, simple rectangular footprint sitting flat on the ground, the whole object visible with empty margin around it, centered, light from the upper left, no cast shadow, no ground under it, plain flat light grey background #B0B0B0, no text, no frame, square 1:1, dark fantasy video game asset, hand-painted digital painting, muted earthy palette with warm torchlight accents, rich surface texture detail, soft moody light from the upper left, Grim Soul and Diablo II art style, for a top-down action RPG
```

## Порядок

1. **Сначала проба из трёх картинок:** `ground-cobble`, `obj-house-a`, `fig-hero-m`. Пришлите их, я соберу из них площадку на телефоне. Земля и дом пришли 07.10. Земля легла хорошо. Дом вышел в изометрии, её взяли за образец ракурса, но сам дом «кривоват»: левая и правая стены нарисованы под разными углами, будто с двух точек зрения. Поэтому в промпт добавлена строгая изометрия: без точек схода, вертикали строго вертикальны, обе стены под 30°. Землю движок теперь кладёт тоже в изометрии, под 45° со сжатием 2:1, чтобы подошва дома лежала по ней. Так станет понятно, работают ли ракурс и масштаб, прежде чем тратить время на остальное.
2. Если проба удалась, делаете остальную партию.
3. **Куда класть.** Можно присылать сюда в чат или класть в `content/art/topdown/` с именами из таблиц (`ground/…`, `objects/…`, `figures/…`), в png. Фон вырежу, размеры подгоню, швы уберу сам.
4. **Если из нескольких вариантов не ясно, какой брать:**
   - **земля** — без перспективы, без крупных пятен, которые будут повторяться узором, без предметов;
   - **предмет или фигура** — не обрезаны, ракурс как у остальных, свет слева сверху.

## Пробная партия: улица у ворот и порт

### Земля (папка `ground/`, промпт: описание + [GROUND] + [STYLE])

| Файл | Что | Описание |
|---|---|---|
| `ground-cobble.png` | булыжная мостовая | `old worn cobblestone street pavement, rounded grey and brown stones of mixed sizes, dirt and moss in the gaps, puddle stains` |
| `ground-dirt.png` | утоптанная дорога | `packed muddy dirt road, wheel ruts, scattered small pebbles and straw` |
| `ground-grass.png` | трава | `dark wild grass, uneven tufts, patches of bare earth and fallen leaves` |
| `ground-planks.png` | доски причала | `weathered wooden pier planks running horizontally, dark wet wood with rusty nails and gaps between boards` |
| `ground-water.png` | морская вода у берега | `dark murky sea water surface, gentle ripples, faint green tint, no reflections of objects` |

### Обстановка (папка `objects/`, промпт: описание + [OBJECT] + [STYLE])

«Размер в игре» — в метрах, по нему я масштабирую. Генератору его передавать не нужно.

| Файл | Что | Размер в игре | Описание |
|---|---|---|---|
| `obj-house-a.png` | дом, большой | 8 × 6 м | `a two-storey medieval town house, stone ground floor, dark timber frame upper floor, steep slate roof, small glowing windows, wooden door` |
| `obj-house-b.png` | дом, маленький | 5 × 4 м | `a small poor medieval cottage, rough stone walls, sagging thatched roof, one lit window` |
| `obj-gate.png` | городские ворота | 10 × 4 м | `a fortified medieval city gate, two squat stone towers with a wooden gate between them, iron bands, torches on the walls` |
| `obj-wall.png` | кусок городской стены | 6 × 2 м | `a straight horizontal section of a medieval stone city wall with battlements, the left and right ends cut flat so sections can join` |
| `obj-tree.png` | дерево | 5 × 5 м | `a large gnarled oak tree with a dense dark green crown seen from above, thick roots` |
| `obj-tree-dead.png` | сухое дерево | 4 × 4 м | `a dead leafless twisted tree, black bark, crooked branches` |
| `obj-rocks.png` | камни | 2 × 1.5 м | `a cluster of three mossy grey boulders` |
| `obj-bush.png` | куст | 1.5 × 1.5 м | `a dark thorny bush with a few red berries` |
| `obj-barrel.png` | бочка | 0.8 м | `an old oak barrel with rusty iron hoops, slightly battered` |
| `obj-crates.png` | ящики | 1.5 м | `a stack of three worn wooden cargo crates tied with rope` |
| `obj-cart.png` | телега | 3 × 1.5 м | `an old wooden two-wheeled cart loaded with sacks` |
| `obj-brazier.png` | жаровня | 0.8 м | `an iron brazier on three legs with burning coals and small flames` |
| `obj-well.png` | колодец | 2 м | `a round stone well with a small wooden roof and a bucket on a rope` |
| `obj-stall.png` | торговая лавка | 3 × 2 м | `a market stall with a striped faded cloth awning, baskets of goods on the counter` |
| `obj-boat.png` | лодка | 4 × 1.5 м | `a small wooden rowing boat with two oars, worn paint, lying on the water` |
| `obj-mooring.png` | швартовая тумба | 0.6 м | `a thick wooden mooring post wrapped with rope` |

### Фигуры (папка `figures/`, промпт: описание + [FIGURE] + [STYLE])

Зверям «standing in a calm idle pose» меняется на «in a calm idle stance on all fours».

| Файл | Кто | Описание |
|---|---|---|
| `fig-hero-m.png` | герой | `a young male adventurer in a simple padded gambeson, leather belt with pouches, short sword at the hip, brown cloak, short dark hair` |
| `fig-hero-f.png` | героиня | `a young female adventurer in a simple padded gambeson, leather belt with pouches, short sword at the hip, brown cloak, braided hair` |
| `fig-npc-beginner.png` | Привратник Уин | `an old gatekeeper in a worn grey cloak holding a lantern and a ring of iron keys, kind tired face, wooden staff` |
| `fig-npc-guard.png` | стражник | `a city guard in a dented steel helmet and chainmail with a tabard, holding a long spear` |
| `fig-npc-boy.png` | Джон, мальчишка в порту | `a scrappy street boy in a flat cap and patched clothes, barefoot, mischievous grin` |
| `fig-mob-rat.png` | крыса | `a giant filthy sewer rat the size of a dog, matted fur, red eyes, long scaly tail` |
| `fig-mob-spider.png` | паук | `a giant hairy cave spider the size of a dog, eight legs spread, glistening black eyes` |
| `fig-ani-hen.png` | курица | `a speckled farmyard hen` |

Позже понадобится ещё одна поза: удар для героя и атака для монстров. Её добавлю, только если без неё бой будет смотреться пусто. Одну и ту же фигуру в двух позах генераторы держат плохо.
