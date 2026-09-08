# Resumo de notificações no S24 Ultra — backend local via CPU (v1)

Status: **funcionando**. Resumos `ActionPointSummary` (bullets com ✨) gerados
on-device e exibidos nas notificações (colapsadas).

## Por que não o NPU (resumo da investigação)

- O `offline.languagemodel` (3.0.00.18 e 4.0.01.19) embarca LLM Gauss compilado
  **só para HTP arch v79/v81** (`htp_backend_ext_config.json`: `soc_id 87,
  dsp_arch v81`). O S24 Ultra (SM8650/pineapple) tem HTP **v75**.
- Log prova a falha: `SNAP_QNN_: Unsupported HTP arch 79` → `QnnDevice_create
  0x36b1`, depois falta `libQnnHtpV75Skel.so` em `/vendor/dsp/{,cdsp}`.
- `/vendor/lib64/snap/` tem o backend stock (`libQnnHtp.so`,
  `libQnnHtpV75Stub.so`) — conecta, mas não executa binário v79.
- Notes/gravador resumem via **nuvem** (`LlmCloudUsageRequestExecutor` no dex
  do Notes). Resumo de notificação é on-device por privacidade → sem fallback.
- QNN CPU/GPU impossível: sem `libQnnCpu/libQnnGpu` e o binário é
  específico do backend HTP. TVM/SLLM no APK não têm pesos para o Gauss.
- Conclusão: NPU Samsung fechado sem artefato proprietário. Solução = outro
  runtime de CPU.

## Arquitetura v1 (este branch)

```
system_server (NotiSummaryManager) ──FEATURE_AI_GEN_SUMMARY──▶ hook LSPosed
        │  LlmServiceRunnable.execute() interceptado (beforeHook + setResult)
        ▼
HTTP 127.0.0.1:18089 (/v1/chat/completions, chat template Qwen)
        ▼
llama-server (CPU, 6 threads) + Qwen2.5-1.5B-Instruct Q4_K_M (~1 GB)
        ▼
Result(content≤400ch, safety={"Blocked":false}) ─▶ fluxo original ─▶ UI
```

- Geração: ~13 s/resumo (prefill ~57 tok/s, decode ~14 tok/s, 8 threads).
- Render: framework `ConversationLayout` mostra `android.summarization`
  **só com a notificação recolhida** (`isShowingSummarization =
  hasContent && collapsed`). Expandida mostra as mensagens cruas — é o
  comportamento original, não bug.
- `NotiSummaryManager.isFresh` forçado a `false` (senão o sistema descarta
  resumos de conversas ativas — `mWaitMsGroup` padrão 9 min).

## Arquivos (neste branch)

- `app/src/main/java/dev/rios/fold8spoof/MainHook.java` — spoofs existentes +
  `hookSummaryInference` (intercepta `LlmServiceRunnable.execute` da feature
  `FEATURE_AI_GEN_SUMMARY`, chama o servidor, completa a Task e pula o
  original) + `hookFreshness` (`isFresh → false`) + normalização pt-BR→pt.
- `.../LlmServerService.java` — hospeda o `llama-server` em
  `getFilesDir()/llm`, porta 18089, com watchdog de 60 s. Se a porta já
  estiver ocupada, fica ocioso (permite um servidor root primário).
- `.../MainActivity.java` — activity launcher (abre o app 1x para iniciar).
- `app/src/main/AndroidManifest.xml` — permissão INTERNET + service exportado.
- `app/src/main/assets/llm/` — `llama-server` + 10 `.so` (Termux `llama-cpp`
  b9590, Android/aarch64, ~27 MB). **Modelo NÃO vai no git** (ver abaixo).
- `app/build.gradle` — `targetSdk 28` (domínios SELinux `untrusted_app_34+`
  proíbem exec de `app_data_file`; com 28 o exec funciona) e `chmod 555/444`
  nos binários (W^X do Android 15).

## Modelo (fora do git, já no aparelho)

- `Qwen2.5-1.5B-Instruct Q4_K_M` (~1,1 GB):
  `https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf`
- Destino: `/data/data/dev.rios.fold8spoof/files/llm/model.gguf` (via root +
  `nsenter -t 1 -m`, `chown <uid>:<uid>`, pois o `su` do Termux roda em
  mount namespace isolado).

## Operação (sem reboot, sem mount, sem partição de sistema)

1. Build: `gradle -p Fold8FeatureSpoofer :app:assembleDebug`
   (Gradle 8.10+, `ANDROID_HOME` apontando ao SDK, JDK 17+).
2. `adb install -r app-debug.apk` (mesma assinatura debug → update preserva
   o scope do LSPosed).
3. `am start -n dev.rios.fold8spoof/.MainActivity` (1ª vez; cria datadir).
4. Posicionar `model.gguf` (item Modelo).
5. Servidor raiz (sobrevive ao restart do zygote, já aquecido):
   `LD_LIBRARY_PATH=... ./llama-server -m model.gguf --host 127.0.0.1
   --port 18089 -c 2048 -t 6 -n 160 --no-webui --parallel 1`
6. `setprop ctl.restart zygote` ( **máximo permitido** — nunca reboot: o root
   via exploit morreria). Reabrir Termux + `sshd` depois.
7. Teste: rajada de mensagens + **tela apagada** (gate obrigatório:
   `checkDeviceStateForSummary` exige `isScreenOff`, bateria > 30%, sem
   powersave). Ver o LSPosed log: `local CPU summary served` → `task success`.

## Troubleshooting

- `error=13` ao executar servidor no app → domínio SELinux (manter
  `targetSdk 28`) e perms 555/444 (W^X).
- `alarm is already registered` / `screen is on` — aguardar, apagar a tela.
- Resumo some ao expandir — normal (só renderiza recolhido).
- Primeira carga do modelo ~3–10 min (dispositivo com swap pressionado);
  depois ~13 s/resumo. Servidor root não precisa recarregar após zygote
  restart.

## Exibição (verificado no aparelho)

- O texto chega ao `mSummarization` (SUCCESS) e ao extra
  `android.summarization` via `SummarizationDecorator` (telemetria no ar:
  `sysui decorateSummarization called, text=Nch`).
- O template mostra o resumo na notificação **expandida**; na **recolhida**
  aparece a última mensagem crua (comportamento do template
  `ConversationLayout` neste build).
- Cada mensagem nova zera o registro (status NONE) e o próximo ciclo
  (alarme ~10 s após tela apagada + inferência ~13 s) resume de novo.
- Hook `hookSuccessRenotify`: re-notifica +3 s após cada SUCCESS para
  forçar rebind depois do extra (corrige race ranking→extra→rebind).

## v2 planejado — LiteRT + NPU (Gemma3-1B sm8650)

- Google distribui `Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm` (~690 MB,
  compilado para este SoC) + runtime NPU v75 (split
  `qualcomm_npu_runtime_v75` do Edge Gallery, já no aparelho).
- No aparelho já existe `Gemma-4-E2B-it.litertlm` (2,4 GB) com cache
  xnnpack (rodou via CPU; mldrift/NPU não completou).
- Migração: trocar o backend no app (LiteRT-LM via Maven, JNI — sem
  `exec()`, sem problema de domínio) mantendo o hook; NPU carrega no
  trigger via LiteRT (warm eager opcional). Tudo continua dentro do módulo
  LSPosed (app + hook), sem mounts.
