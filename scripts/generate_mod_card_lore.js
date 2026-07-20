const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const dryRun = process.argv.includes("--dry-run");
const percentAffixes = new Set(["critical_chance", "critical_damage", "status_chance", "summon_damage"]);
const weaponCategories = new Set(["melee_light", "melee_heavy", "polearm", "bow", "crossbow", "firearm"]);
const meleeCategories = new Set(["melee_light", "melee_heavy", "polearm"]);
const rangedCategories = new Set(["bow", "crossbow", "firearm"]);
const armorCategories = new Set(["armor_physical", "armor_magic"]);
const categoryLabels = new Map([
  ["melee_light", "轻近战"], ["melee_heavy", "重近战"], ["polearm", "长柄武器"],
  ["bow", "弓"], ["crossbow", "弩"], ["firearm", "枪械"],
  ["armor_physical", "物理护甲"], ["armor_magic", "法术护甲"],
  ["summon", "召唤武器"], ["pickaxe", "镐"]
]);
const slotLabels = new Map([["head", "头盔"], ["chest", "胸甲"], ["legs", "护腿"], ["feet", "靴子"]]);
const triggerLabels = new Map([
  ["left", "左键"], ["right", "右键"], ["shift_left", "Shift+左键"],
  ["shift_right", "Shift+右键"], ["shift_f", "Shift+F"], ["midair", "空中"]
]);
const colorCodes = new Map([
  ["0", "black"], ["1", "dark_blue"], ["2", "dark_green"], ["3", "dark_aqua"],
  ["4", "dark_red"], ["5", "dark_purple"], ["6", "gold"], ["7", "gray"],
  ["8", "dark_gray"], ["9", "blue"], ["a", "green"], ["b", "aqua"],
  ["c", "red"], ["d", "light_purple"], ["e", "yellow"], ["f", "white"]
]);
const elementColors = new Map([
  ["heat_damage", "red"], ["cold_damage", "aqua"],
  ["toxin_damage", "green"], ["electric_damage", "yellow"]
]);
const placeholderPattern = /%cfg([st]?):([^%|]+)(?:\|([^%]*))?%/g;
const loreBlockPattern = /^      lore:\r?\n(?:        -.*(?:\r?\n|$))*/m;

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function unquote(value) {
  const trimmed = value.trim();
  if (trimmed.length >= 2 && ["\"", "'"].includes(trimmed[0]) && trimmed.at(-1) === trimmed[0]) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function scalar(text, key) {
  const match = text.match(new RegExp(`^\\s*${escapeRegex(key)}:\\s*(.*?)\\s*$`, "m"));
  return match ? unquote(match[1]) : null;
}

function list(text, key) {
  const raw = scalar(text, key);
  if (!raw || !raw.startsWith("[") || !raw.endsWith("]")) return [];
  return raw.slice(1, -1).split(",").map(unquote).filter(Boolean);
}

function description(text) {
  const lines = text.split(/\r?\n/);
  const values = [];
  let collecting = false;
  for (const line of lines) {
    if (line === "description:") {
      collecting = true;
      continue;
    }
    if (!collecting) continue;
    if (line.startsWith("  - ")) {
      values.push(unquote(line.slice(4)));
    } else {
      break;
    }
  }
  return values;
}

function effects(text) {
  const raw = scalar(text, "effects");
  if (!raw || !raw.startsWith("{") || !raw.endsWith("}")) return [];
  return [...raw.matchAll(/([A-Za-z0-9_]+)\s*:\s*(-?[0-9]+(?:\.[0-9]+)?)/g)]
    .map(([, identifier, value]) => [identifier, Number(value)]);
}

function parseAffixes(file) {
  const text = fs.readFileSync(file, "utf8");
  const affixes = new Map();
  const blockPattern = /^  ([a-z0-9_]+):\r?\n([\s\S]*?)(?=^  [a-z0-9_]+:\r?\n|(?![\s\S]))/gm;
  for (const match of text.matchAll(blockPattern)) {
    const [, identifier, block] = match;
    affixes.set(identifier, {
      displayName: scalar(block, "display-name") ?? identifier,
      decimals: Number(scalar(block, "decimals") ?? 1),
      percent: (scalar(block, "percent") ?? "false").toLowerCase() === "true" || percentAffixes.has(identifier),
      color: scalar(block, "color") ?? ""
    });
  }
  return affixes;
}

function parseMod(file) {
  const text = fs.readFileSync(file, "utf8");
  const identifier = scalar(text, "id");
  if (!identifier) throw new Error(`Missing id in ${file}`);
  return {
    identifier,
    cost: Number(scalar(text, "cost") ?? 0),
    maxRank: Number(scalar(text, "max-rank") ?? 0),
    tags: list(text, "tags").map((value) => value.toLowerCase()),
    effects: effects(text),
    categories: new Set(list(text, "applicable-categories").map((value) => value.toLowerCase())),
    equipment: list(text, "applicable-equipment"),
    slots: list(text, "applicable-slots").map((value) => value.toLowerCase()),
    skill: (scalar(text, "skill") ?? "false").toLowerCase() === "true",
    passiveSkill: (scalar(text, "passive-skill") ?? "false").toLowerCase() === "true",
    triggers: list(text, "allowed-triggers").map((value) => value.toLowerCase()),
    typeLabel: scalar(text, "type-label"),
    mana: scalar(text, "mana"),
    cooldown: scalar(text, "cooldown"),
    description: description(text)
  };
}

function sameSet(left, right) {
  return left.size === right.size && [...left].every((value) => right.has(value));
}

function number(value, decimals) {
  if (decimals <= 0) return String(Math.round(value));
  return value.toFixed(decimals).replace(/\.0+$|(?<=\.[0-9]*?)0+$/u, "").replace(/\.$/, "");
}

function affixValue(affix, value) {
  if (affix.percent) return `${number(value * 100, Math.max(affix.decimals - 2, 0))}%`;
  return number(value, affix.decimals);
}

function legacyToMiniMessage(text) {
  return text.replace(/&([0-9a-fk-or])/gi, (_, rawCode) => {
    const code = rawCode.toLowerCase();
    if (colorCodes.has(code)) return `<${colorCodes.get(code)}>`;
    if (code === "r") return "<reset>";
    if (code === "l") return "<bold>";
    if (code === "o") return "<italic>";
    return "";
  });
}

function resolveDefaults(text) {
  return text.replace(placeholderPattern, (_, unit, _path, fallback) => {
    let value = Number(fallback ?? 0);
    if (unit === "s") value /= 1000;
    if (unit === "t") value /= 20;
    return number(value, 2);
  });
}

function typeLabel(mod) {
  if (mod.typeLabel) return mod.typeLabel;
  if (mod.tags.includes("test")) return "测试";
  if (mod.passiveSkill) return "护甲被动";
  if (mod.skill || mod.tags.includes("skill")) return "技能";
  if (mod.tags.includes("elemental")) return "元素";
  return "属性";
}

function scopeLabel(mod) {
  if (mod.equipment.length) return mod.equipment.join(" · ");
  if (mod.slots.length && sameSet(mod.categories, armorCategories)) {
    return mod.slots.map((slot) => slotLabels.get(slot) ?? slot).join(" · ");
  }
  let base;
  if (!mod.categories.size) base = "通用";
  else if (sameSet(mod.categories, weaponCategories)) base = "武器通用";
  else if (sameSet(mod.categories, meleeCategories)) base = "近战武器";
  else if (sameSet(mod.categories, rangedCategories)) base = "远程武器";
  else if (sameSet(mod.categories, armorCategories)) base = "护甲通用";
  else base = [...mod.categories].map((category) => categoryLabels.get(category) ?? category).join(" · ");
  if (!mod.slots.length) return base;
  return `${base}（仅${mod.slots.map((slot) => slotLabels.get(slot) ?? slot).join(" · ")}）`;
}

function effectLine(affix, identifier, value, nextValue) {
  const color = affix.color ? legacyToMiniMessage(affix.color) : `<${elementColors.get(identifier) ?? "white"}>`;
  const current = `${value < 0 ? "-" : "+"}${affixValue(affix, Math.abs(value))}`;
  let line = `<!i>  <dark_gray>▪ <gray>${affix.displayName}  ${color}${current}`;
  if (nextValue !== null) {
    const next = `${nextValue < 0 ? "-" : "+"}${affixValue(affix, Math.abs(nextValue))}`;
    line += ` <dark_gray>→ <green>${next}`;
  }
  return line;
}

function buildLore(mod, affixes) {
  const lines = [`<!i><dark_gray>◆ <gray>${typeLabel(mod)} MOD  <dark_gray>│  <gray>占用 <yellow>${mod.cost}`];
  if (mod.skill) {
    const triggers = mod.triggers.length ? mod.triggers.map((trigger) => triggerLabels.get(trigger) ?? trigger).join("/") : "任意";
    lines.push(`<!i><dark_gray>◆ <gray>触发方式  <white>${triggers} <dark_gray>· <gray>技能栏`);
  }
  if (mod.passiveSkill) lines.push("<!i><dark_gray>◆ <gray>生效方式  <white>装备后生效 <dark_gray>· <gray>被动技能槽");
  if (mod.maxRank > 0) lines.push(`<!i><dark_gray>◆ <gray>段位  <aqua>${"◇".repeat(mod.maxRank)} <gray>0/${mod.maxRank}`);
  if (mod.effects.length) {
    lines.push("", "<!i><light_purple>✦ 属性增幅");
    for (const [identifier, maximum] of mod.effects) {
      const affix = affixes.get(identifier) ?? { displayName: identifier, decimals: 1, percent: false, color: "" };
      const current = mod.maxRank > 0 ? maximum / (mod.maxRank + 1) : maximum;
      const next = mod.maxRank > 0 ? maximum * 2 / (mod.maxRank + 1) : null;
      lines.push(effectLine(affix, identifier, current, next));
    }
  }
  const skillish = mod.skill || mod.passiveSkill || mod.tags.includes("skill");
  const metadata = [];
  if (skillish && mod.mana) metadata.push(`<!i>  <dark_gray>▪ <gray>能量消耗  <aqua>${legacyToMiniMessage(resolveDefaults(mod.mana))}`);
  if (skillish && mod.cooldown) metadata.push(`<!i>  <dark_gray>▪ <gray>冷却时间  <white>${legacyToMiniMessage(resolveDefaults(mod.cooldown))}`);
  const descriptionLines = mod.description.map((line) => `<!i>  <dark_gray>▪ ${legacyToMiniMessage(resolveDefaults(line))}`);
  if (skillish && (metadata.length || descriptionLines.length)) lines.push("", "<!i><aqua>✦ 技能效果", ...metadata, ...descriptionLines);
  else if (descriptionLines.length) lines.push("", "<!i><gold>✦ 效果说明", ...descriptionLines);
  lines.push("", `<!i><gray>适用装备  <white>${scopeLabel(mod)}`);
  return lines;
}

function replaceLore(file, lore) {
  const text = fs.readFileSync(file, "utf8");
  if (!loreBlockPattern.test(text)) throw new Error(`Could not find lore block in ${file}`);
  const newline = text.includes("\r\n") ? "\r\n" : "\n";
  const replacement = `      lore:${newline}${lore.map((line) => `        - ${JSON.stringify(line)}${newline}`).join("")}`;
  const updated = text.replace(loreBlockPattern, replacement);
  if (updated === text) return false;
  if (!dryRun) fs.writeFileSync(file, updated, "utf8");
  return true;
}

function main() {
  const modsDir = path.join(root, "src", "main", "resources", "mods");
  const affixFile = path.join(root, "src", "main", "resources", "affixes.yml");
  const itemsDir = path.join(root, "craftengine", "sourceforge", "configuration", "items");
  const affixes = parseAffixes(affixFile);
  let changed = 0;
  let skipped = 0;
  for (const name of fs.readdirSync(modsDir).filter((entry) => entry.endsWith(".yml")).sort()) {
    const mod = parseMod(path.join(modsDir, name));
    const itemFile = path.join(itemsDir, `mod_${mod.identifier}.yml`);
    if (!fs.existsSync(itemFile)) {
      skipped += 1;
      continue;
    }
    if (replaceLore(itemFile, buildLore(mod, affixes))) changed += 1;
  }
  console.log(`${dryRun ? "Would update" : "Updated"} ${changed} static MOD lore files; skipped ${skipped} without CraftEngine cards.`);
}

main();
