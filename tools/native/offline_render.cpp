// offline_render.cpp — SUPERSEDED, kept only as a historical note.
//
// The working offline renderer is tools/native/offline_render.c.  This file
// was an earlier attempt that drove the engine through a guessed private ABI
// (a { get, set, process, release, get_descriptor } vtable and a hand written
// effect_config_t) and crashed on the device.
//
// The correct ABI, taken from the engine's own header
// (libjamesdsp/jni/jamesdsp/essential.h, the AOSP audio_effect.h layout), is:
//
//   struct effect_interface_s {
//       int32_t (*process)(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out);
//       int32_t (*command)(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
//                          void *pCmdData, uint32_t *replySize, void *pReplyData);
//       int32_t (*get_descriptor)(effect_handle_t self, effect_descriptor_t *pDescriptor);
//       int32_t (*process_reverse)(...);
//   };
//
// with effect_handle_t pointing at a struct whose first member is
// "const struct effect_interface_s *itfe", and
//
//   effect_config_t { buffer_config_t inputCfg; buffer_config_t outputCfg; }
//
// See docs/ACCEPTANCE-SIGNAL.md for the full analysis.
