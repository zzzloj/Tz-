# Промпты для Unity-версии: фигуры в полный рост и фоны-сцены

Стиль тот же, что у нынешних портретов и картин мест. Это мрачное фэнтези в духе Diablo и Baldur's Gate, «живописная» картинка, драматичный боковой свет.

## Фигуры (монстры, звери, NPC)
В прототипе на месте фигур пока стоят нынешние квадратные портреты с мягким круглым краем. Готовые фигуры заменяют их по одной: файл кладётся в `content/art/figures/<ключ>.png`, ключ тот же, что у портрета.

**Общая часть промпта** (добавлять к каждому):
```
dark fantasy painted character art, full body from head to feet, standing idle pose, three-quarter view turned slightly to the left, whole figure visible with margin around it, dramatic warm rim light from the upper left, rich texture detail, Diablo / Baldur's Gate style, isolated on a plain flat light grey background, no ground, no cast shadow, no text, no frame, vertical 3:4, 1536×2048
```
Фон нужен светло-серый и ровный. Я вырежу его сам и сделаю прозрачным.

**Первая партия** — стартовая зона, именно её покажет прототип:

| Файл | Кто | Промпт (перед общей частью) |
|---|---|---|
| `mob-rat.png` | крыса | `a giant filthy sewer rat with matted fur, red eyes and long scaly tail, on all fours, menacing` |
| `mob-spider.png` | гигантский паук | `a giant hairy cave spider the size of a dog, eight legs spread, glistening black eyes, venom on its fangs` |
| `mob-scorpion.png` | скорпион | `a huge desert scorpion with a dark bronze carapace, raised claws and a curled venomous tail` |
| `mob-snake.png` | змея | `a large coiled swamp viper rearing up to strike, mottled green and brown scales, open jaws with fangs` |
| `mob-orcle.png` | орк-лейтенант | `a brutish orc lieutenant in scavenged spiked leather and iron armour, holding a notched cleaver, green-grey skin, tusks` |
| `ani-hare.png` | заяц | `a wild brown hare sitting alert with long ears, realistic animal` |
| `ani-hen.png` | курица | `a speckled farmyard hen, realistic animal` |
| `npc-beginner.png` | Привратник Уин | `an old gatekeeper in a worn grey cloak with a lantern and a ring of iron keys, kind tired face, staff in hand` |

Остальные монстры, звери и NPC делаются по той же схеме, с теми же ключами, что у портретов (`content/art/mobs.json`, `npcs.json`). Если нравится результат первой партии, пришлю список целиком.

## Фоны-сцены
Пока сценой служат нынешние 34 картины мест (16:9). Это рабочий вариант: нижняя треть там тёмная, на неё встают фигуры. Позже для каждой зоны можно сделать «сцену» с пустой площадкой под фигуры:
```
dark fantasy painted environment, <место>, eye-level view, an empty flat ground area across the lower third where characters will stand, depth and atmosphere, dramatic light, Diablo / Baldur's Gate style, no characters, no text, 16:9, 1920×1080
```
Зоны, начиная со стартовой: городская улица у ворот, склад, лес, дорога в поле, пещера-шахта, кладбище, болото, берег моря, горы, Египет (храм, гробница), тёмный лес орков, Мёртвый город, зал замка, таверна.
