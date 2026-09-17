# SoundBridge — Presets (Perfis de Qualidade) para o Plugin

Referência dos presets de transmissão e o que cada um configura. Para implementar no plugin
JetBrains (a janela de envio pode oferecer esses presets como atalhos).

Os presets definem 5 parâmetros de transmissão: **modulação**, **FEC**, **resync**, **paridade** e
**banda**. Origem: `ConfigDialog` do RX C++ (função `ofdmPresetFor`).

---

## Tabela resumo

| Preset | Modulação | FEC | Resync | Paridade | Banda | Perfil de uso |
|---|---|---|---|---|---|---|
| **AUTO** | (automático) | (automático) | 10 | 16 | 22000 | escolhe pelo tamanho do arquivo |
| **CUSTOM** | (manual) | (manual) | (manual) | (manual) | (manual) | usuário define tudo |
| **RÁPIDO** | 64-QAM | r12 | 25 | 8 | 22000 | rápido, menos overhead |
| **BALANCEADO** | 64-QAM | r12 | 10 | 16 | 22000 | equilíbrio (recomendado geral) |
| **ROBUSTO** | 16-QAM | r12 | 5 | 32 | 22000 | máxima confiabilidade |
| **EXPERIMENTAL** | 256-QAM | r34 | 10 | 16 | 22000 | mais rápido (arquivos pequenos-médios) |

---

## Detalhe de cada preset (com as flags do `soundbridge-tx`)

### AUTO
Escolhe **modulação e FEC automaticamente pelo tamanho do arquivo** (após compressão, se `--zip`).
O plugin passa apenas `--auto`; o TX decide.

| Faixa (tamanho do payload) | Modulação | FEC |
|---|---|---|
| até ~12 KB | 1024-QAM | r12 |
| até ~100 KB | 256-QAM | r34 |
| acima disso | 64-QAM | r12 |

Flags: `--auto` (+ `--band-high 22000 --stereo`; resync/parity ficam no default do TX).
Recomendado como opção padrão do plugin.

### CUSTOM
O usuário define todos os parâmetros manualmente (modulação, FEC, resync, paridade). No plugin,
equivale a "modo manual" — os campos ficam editáveis.

### RÁPIDO
Prioriza velocidade com o 64-QAM e menos overhead de proteção (resync espaçado, paridade menor).

- Modulação: **64-QAM** → `--qam64`
- FEC: **r12** → `--fec r12`
- Resync: **25** → `--resync 25`
- Paridade: **8** → `--parity 8`
- Banda: **22000** → `--band-high 22000`

### BALANCEADO
O equilíbrio recomendado para uso geral. 64-QAM com proteção intermediária.

- Modulação: **64-QAM** → `--qam64`
- FEC: **r12** → `--fec r12`
- Resync: **10** → `--resync 10`
- Paridade: **16** → `--parity 16`
- Banda: **22000** → `--band-high 22000`

### ROBUSTO
Máxima confiabilidade: 16-QAM (menos denso, mais tolerante a ruído) com resync frequente e paridade
máxima. Para canais ruins ou transmissões longas.

- Modulação: **16-QAM** → `--qam16`
- FEC: **r12** → `--fec r12`
- Resync: **5** → `--resync 5`
- Paridade: **32** → `--parity 32`
- Banda: **22000** → `--band-high 22000`

### EXPERIMENTAL
Mais rápido usando 256-QAM (denso) com FEC leve (r34). Confiável em **arquivos pequenos-médios**
(até ~200 KB no cabo); marginal em arquivos grandes. Ideal para arquivos pequenos onde a velocidade
importa.

- Modulação: **256-QAM** → `--qam256`
- FEC: **r34** → `--fec r34`
- Resync: **10** → `--resync 10`
- Paridade: **16** → `--parity 16`
- Banda: **22000** → `--band-high 22000`

---

## Tabelas de mapeamento (valor → flag)

Para o plugin montar o comando a partir de índices internos, se necessário.

### Modulação
| Índice | Nome | Flag |
|---|---|---|
| 0 | QPSK | (nenhuma flag — é o default) |
| 1 | 16-QAM | `--qam16` |
| 2 | 64-QAM | `--qam64` |
| 3 | 256-QAM | `--qam256` |
| 4 | 1024-QAM | `--qam1024` |

### FEC
| Índice | Flag |
|---|---|
| 0 | `--fec none` |
| 1 | `--fec r12` |
| 2 | `--fec r23` |
| 3 | `--fec r34` |

### Resync
| Índice | Flag |
|---|---|
| 0 | `--resync off` |
| 1 | `--resync 10` |
| 2 | `--resync 25` |
| 3 | `--resync 5` |

### Paridade
| Índice | Flag |
|---|---|
| 0 | `--parity off` |
| 1 | `--parity 8` |
| 2 | `--parity 16` |
| 3 | `--parity 32` |

---

## Notas para o plugin

- **AUTO deve ser o padrão** — é o mais simples e seguro (o usuário não precisa entender os termos).
- Quando um preset é selecionado, os campos de modulação/FEC/resync/paridade podem ficar
  **desabilitados** (mostrando os valores do preset), como no RX. Só o CUSTOM os deixa editáveis.
- Todos os presets usam **banda 22000** e **estéreo** (2 canais). Esses podem ficar fixos nos
  Settings do plugin (não precisam aparecer no seletor de preset).
- O preset é só um atalho que preenche os parâmetros; o comando final montado é o mesmo
  (`soundbridge-tx --in <arquivo> <flags do preset> --play --device N`).

---

## Exemplo de comando por preset

```bash
# AUTO
soundbridge-tx --in arquivo.zip --auto --stereo --band-high 22000 --play --device 14

# BALANCEADO
soundbridge-tx --in arquivo.zip --qam64 --fec r12 --resync 10 --parity 16 --stereo --band-high 22000 --play --device 14

# EXPERIMENTAL
soundbridge-tx --in arquivo.zip --qam256 --fec r34 --resync 10 --parity 16 --stereo --band-high 22000 --play --device 14

# ROBUSTO
soundbridge-tx --in arquivo.zip --qam16 --fec r12 --resync 5 --parity 32 --stereo --band-high 22000 --play --device 14

# RÁPIDO
soundbridge-tx --in arquivo.zip --qam64 --fec r12 --resync 25 --parity 8 --stereo --band-high 22000 --play --device 14
```
