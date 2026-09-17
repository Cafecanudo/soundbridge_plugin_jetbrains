# CLAUDE.md — SoundBridge Plugin (JetBrains / IntelliJ)

Contexto para o Claude Code trabalhar neste repositório.

## O que é este repo

Um **plugin para IDEs JetBrains** que transmite arquivos por **áudio** direto da IDE. O usuário
clica com o botão direito num arquivo do projeto → **"Enviar por Áudio"** → abre uma janela com
configurações, barra de progresso, log ao vivo e botão de retentar.

Faz parte do projeto **SoundBridge** (transferência de arquivos usando só som, via modem OFDM).
Competição acadêmica (prazo jan/2027); o plugin vale como **"implementação diferente"**.

- **Gradle + IntelliJ Platform Plugin.** **Kotlin.**
- Alvo inicial: **IntelliJ IDEA Ultimate**, mas escrito para funcionar em **qualquer IDE JetBrains**
  (usar apenas APIs da IntelliJ Platform, sem dependências específicas de produto).

## Decisão de arquitetura (JÁ TOMADA — não reabrir sem motivo)

**O plugin NÃO reimplementa a camada de áudio.** Ele chama o pacote Python **`soundbridge-tx`**
(publicado no PyPI) via **subprocess** (Opção A). O plugin orquestra: monta o comando, executa,
captura a saída e mostra o progresso/log.

- **Integração contra a CLI 1.0.0 publicada (Opção B — DECIDIDO).** O plugin **NÃO usa flags JSON**
  (`--list-devices-json`/`--progress-json` não existem no pacote). Ele parseia a **saída de texto**
  da CLI 1.0.0. Sem dependência de republicar o pacote.
- **Requisito do usuário:** Python ≥3.10 + `soundbridge-tx[live]` instalados. O plugin deve
  **detectar** isso e orientar a instalação (`pip install soundbridge-tx[live]`) se faltar.
- **Comando default dos Settings:** `soundbridge-tx` (entry-point; resolve no PATH). No Windows,
  tratar a resolução do `.exe`/`cmd` no runner.
- Evolução futura (opcional, não agora): portar o core para Kotlin nativo.

## Regra de ouro do projeto

1. **Conversa técnica começa como brainstorming.** Roteiro + opções + trade-offs primeiro. NÃO
   escrever código até uma decisão explícita.
2. **Edições cirúrgicas:** citar o texto exato e usar "Substitua por" / "Adicione ABAIXO". Nunca
   "por volta da linha X". Se >70% de um arquivo/função muda, gerar inteiro.
3. **Sem comentários no código** — explicar as decisões no chat.
4. **Análise crítica ativa:** apontar riscos e discordar com base técnica. Concordância passiva tem
   valor zero.
5. **PT-BR** nas conversas. Termos técnicos em inglês quando são o padrão.
6. **Um passo por vez;** validar cada etapa (buildar/rodar o plugin na IDE) antes de avançar.

> **Metodologia de iteração:** o plugin builda/roda só na máquina do usuário (o SDK IntelliJ não é
> testável no ambiente do assistente). O assistente escreve o Kotlin; o usuário builda/testa na IDE
> e traz os erros. Iterar como se fez com o C++ do RX.

## Componentes do plugin (Kotlin)

| Componente | Papel |
|---|---|
| `plugin.xml` | registra a Action (menu de contexto) e o Settings; compatibilidade com IDEs JetBrains |
| `SendByAudioAction` | a ação "Enviar por Áudio" no menu de contexto de um **arquivo ou pasta** |
| `SendDialog` | a janela de envio: device, presets, WAV, log ao vivo + spinner + Cancelar |
| `SoundbridgeRunner` | executa o `soundbridge-tx` (subprocess): `listDevices()` e `transmit()`; lê o stdout cru em chunks |
| `SoundbridgeCommand` | monta a `List<String>` do comando (função pura) + `tokenize()` |
| `Preset` | os perfis de qualidade (AUTO/RÁPIDO/BALANCEADO/ROBUSTO/EXPERIMENTAL/CUSTOM) |
| `AnsiScreen` | emulador de terminal (cursor-up/clear/CR) p/ a barra do tx atualizar in-place no log |
| `SoundbridgeSettings` | as configurações fixas (persistidas via `PersistentStateComponent`) |
| `SettingsConfigurable` | a tela de Settings da IDE (Preferences → Tools → SoundBridge) |

### Fluxo
1. Botão direito num arquivo no Project View → **"Enviar por Áudio"**.
2. Abre o `SendDialog` (parâmetros selecionáveis).
3. Ao **Enviar**: o `SoundbridgeRunner` roda `soundbridge-tx --in <arquivo> --play --device N ...`,
   lê o stdout de texto linha a linha, alimenta o log ao vivo e a barra (indeterminada).
4. Sucesso = `exitCode 0` + linha `crc32=...` vista. Falha (exit≠0 / traceback) → mostra
   **Retentar** (reexecuta com os mesmos parâmetros).

## Divisão dos parâmetros (DECISÃO TOMADA)

**Fixos — nos Settings do plugin (uma vez):** comando do `soundbridge-tx` (ou caminho do Python),
`band-high` (22000), `stereo` (sim), `peak`, `guard` (avançados, opcionais). `--verbose` é sempre ligado.

**Selecionáveis — na Janela de Envio (a cada envio):**
- **Device** de saída (dropdown, via `--list-devices` parseado). Aceita **arquivo ou pasta** (`--in <dir>`).
- **Qualidade (preset)** — barra segmentada (`SegmentedButton`): AUTO · RÁPIDO · BALANCEADO · ROBUSTO ·
  EXPERIMENTAL · CUSTOM. O preset preenche e **trava** modulação + FEC + resync + paridade; só **CUSTOM**
  libera esses 4 campos; **AUTO** passa só `--auto`. Ver [PRESETS-HANDOFF.md](PRESETS-HANDOFF.md).
- **Gerar WAV** (checkbox) — em vez de tocar, gera `<pasta>/<nome>.wav` (`--out`); o botão vira "Salvar".
  Desabilitado para pasta.
- **zip** (checkbox, **default ON**), **name** (default = nome do arquivo; não se aplica a pastas),
  **profile** (opcional), **copymemory** (checkbox).

> **`resync`/`parity` saíram dos Settings** — agora são por-envio (definidos pelo preset ou pelo CUSTOM).
> No AUTO, o plugin **não emite** `--resync/--parity` (usa o default do tx).

A janela **persiste as últimas escolhas** (preset, device, mod/FEC/resync/parity do CUSTOM, zip, profile,
copymemory, WAV) — **exceto o nome**, que é sempre o do arquivo selecionado.

## Como o plugin consome o pacote (contrato de parse — CLI 1.0.0, Opção B)

O plugin parseia a **saída de texto** do `soundbridge-tx 1.0.0` publicado. Contratos travados a
partir da saída real na máquina do usuário (Windows).

### `--list-devices` → dropdown de devices
Saída real (ignorar a linha de cabeçalho `Dispositivos de saida disponiveis:`):
```
  [12] Alto-falantes (G733 Gaming Headset)  2ch  48000Hz  [Windows WASAPI]
  [4] Alto-falantes (G733 Gaming Head  2ch  44100Hz  [MME] (default)
```
Regex por linha:
```
^\s*\[(\d+)\]\s+(.*?)\s{2,}(\d+)ch\s{2,}(\d+)Hz\s{2,}\[([^\]]+)\](\s+\(default\))?\s*$
```
→ `index`, `name` (pode vir **truncado** pela CLI), `channels`, `sampleRate`, `api`, `isDefault`.
Separador entre campos é **2+ espaços**; o nome usa espaço simples (o `.*?` não-guloso corta certo).

### Progresso / sucesso (`--play`, mesmo formato do `--out`)
Saída real:
```
auto: payload 4 bytes -> 1024-QAM + r12
[###################################] Escrevendo WAV
crc32=0xC9FDD05E  payload=4  dados=2  band_high=22000  resync=10  parity=16
```
Mapeamento no `SoundbridgeRunner`:
- Barra `[###...]` **NÃO tem percentual** → **barra INDETERMINADA (spinner)** na v1. (Determinismo de
  % fica para depois, só se um run `--play` real provar que a barra redesenha com largura contável.)
- `auto: payload (\d+) bytes -> (.+)` → linha de log (decisão do auto).
- Qualquer outra linha (ex. `Escrevendo WAV`; no `--play` será outro rótulo) → **stream ao log**
  (não hardcodar rótulo).
- **Linha de sucesso ("done"):**
  `crc32=(0x[0-9A-Fa-f]+)\s+payload=(\d+)\s+dados=(\d+)\s+band_high=(\d+)\s+resync=(\S+)\s+parity=(\S+)`
- **Regra final:** `exitCode==0` **e** linha `crc32=` vista → sucesso. Senão (exit≠0 ou traceback no
  stderr) → falha → **Retentar**.

### Regras de execução do subprocess (do contrato)
1. **Forçar UTF-8:** env `PYTHONUTF8=1` + `PYTHONIOENCODING=utf-8`, ler stdout como UTF-8 (sem isso
   a saída vem com mojibake — ex. "saida"/"disponiveis" sem acento).
2. **SEMPRE passar `--device N`.** `--play` sem device é **interativo** (pergunta no stdin) → trava
   o subprocess.
3. **QPSK = ausência de flag** (não existe `--qpsk`; no modo manual, QPSK ⇒ não emitir modulação).
4. **Dropdown:** não pré-selecionar o `(default)` do sistema (aqui é MME 44100Hz — errado). Ordem:
   último device usado (persistido) → um **WASAPI 48000** → default. O RX exige **48000 Hz**; avisar
   se o device escolhido for ≠48000.

## Parâmetros do soundbridge-tx (referência do comando montado)

`--in ARQUIVO` (ou `--text`) · `--play --device N` (ou `--out X.wav`) · `--stereo` ·
`--band-high N` · `--auto` (ou `--qam16/64/256/1024`; QPSK = **sem flag**) · `--fec none/r12/r23/r34` ·
`--resync off/10/25/5` · `--parity off/8/16/32` · `--zip` · `--name` · `--profile` ·
`--copymemory` · `--verbose` (sempre) · `--list-devices`.

**Modo `--auto`:** ≤12KB → 1024-QAM+r12; ≤100KB → 256-QAM+r34; acima → 64-QAM+r12.

## Ordem de implementação

> **Passo 0 (feito):** contrato de parse da CLI 1.0.0 travado a partir da saída real (ver seção
> "Como o plugin consome o pacote"). Não há mais dependência de republicar o pacote.

1. ✅ **Esqueleto:** Gradle + IntelliJ Platform Plugin (1.x), `plugin.xml`, `SendByAudioAction` no menu.
2. ✅ **Settings:** `SoundbridgeSettings` + `SettingsConfigurable`.
3. ✅ **SendDialog:** janela + dropdown de devices (parse do `--list-devices`).
4. ✅ **SoundbridgeRunner:** subprocess, log ao vivo (com `AnsiScreen`), sucesso pelo `crc32=`/exitCode.
   \+ ✅ **Presets** (`SegmentedButton`), modo **WAV**, envio de **pasta**, persistência de escolhas.
5. ⬜ **Retentar** + tratamento de erros + detecção de ambiente (mensagem/CTA de instalação na abertura).

## Contexto do projeto SoundBridge

- **RX (receptor):** app C++/Qt no repo `github.com/Cafecanudo/soundbridge`.
- **TX (transmissor):** pacote Python `soundbridge-tx` no repo `github.com/Cafecanudo/soundbridge_tx`
  (o que este plugin chama).
- **Modulação recomendada:** 64-QAM (~12 KB/s, robusto). O `--auto` escolhe pelo tamanho.
- O device de saída do plugin deve estar ligado ao cabo de áudio que vai para o PC receptor.

## Armadilhas a antecipar

- **Detecção de ambiente:** rodar `soundbridge-tx --list-devices` no início; se falhar (Python ou
  pacote ausente), mostrar mensagem clara com o comando de instalação (`pip install soundbridge-tx[live]`).
- **Subprocess interativo:** `--play` **sem** `--device` pergunta no stdin e trava. O runner SEMPRE
  passa `--device N`.
- **Encoding no Windows:** forçar UTF-8 (`PYTHONUTF8=1`, `PYTHONIOENCODING=utf-8`) e ler stdout como
  UTF-8; a saída da CLI vem com mojibake sem isso. Ler linha a linha.
- **Barra sem percentual:** o progresso é `[###...]` sem número → barra indeterminada; o valor está
  no log ao vivo + sucesso pelo `crc32=`/exitCode.
- **Device errado:** o `(default)` do sistema pode não ser 48000Hz (o RX exige 48000). Preferir
  WASAPI 48000; avisar em caso de ≠48000.
- **Compatibilidade entre IDEs JetBrains:** não usar APIs específicas do IntelliJ IDEA; declarar a
  compatibilidade correta no `plugin.xml` (sem `<depends>` de módulos específicos de produto além
  do `com.intellij.modules.platform`).
- **Threading:** o subprocess roda fora da EDT; atualizar a UI (barra/log) via
  `ApplicationManager.getApplication().invokeLater { ... }`.
