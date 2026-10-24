# CORD (自研) — 音频通道路径禁用

作用：把 FAST / RAW / COMPRESS_OFFLOAD / MMAP_NOIRQ / DIRECT 这几类绕过 mixer 的
输出通道从所有模块的 audio_policy*.xml / .conf 里删掉，使 AAudio / OpenSL ES /
低延迟会话全部落到 primary / deep_buffer，从而能被 postprocess 的效果处理。

关键点（v2.4 修复）：

- 旧版 sed 用 's/AUDIO_OUTPUT_FLAG_FAST //g'（带空格）匹配，而实际写法是
  flags="AUDIO_OUTPUT_FLAG_FAST"（带引号）→ 从未生效。现在直接删除 token
  本身，再把变空的 flags="" 换成 AUDIO_OUTPUT_FLAG_NONE。
- 运行时真正加载的 policy 不一定是 /vendor/etc/audio_policy_configuration.xml：
  MIUI 上是 /vendor/etc/audio_policy_configuration_a2dp_offload_disabled_cg_enabled.xml
  （dumpsys media.audio_policy | grep 'Config source' 可确认）。脚本对
  /vendor/etc、/system_ext/etc、/odm/etc、/my_product/etc 全量扫描。
- 同时修改其它模块里的同名副本，保证挂载竞争时胜出的那份也是已打补丁版本。

安装：zip -qr CORD.zip . 然后 ksud module install CORD.zip，重启生效。
