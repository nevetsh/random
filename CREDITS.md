# Credits

MeowMod itself is MIT licensed (see the `license` field in `src/main/resources/fabric.mod.json`).
Two modules are ports of other people's work and stay under the licence that work came with, so the
notices belong here.

## OdinClient — BSD 3-Clause

`Auto Harp` ([AutoHarp.kt](src/main/kotlin/com/meow/mod/features/AutoHarp.kt)) and
`Auto Experiments` ([AutoExperiments.kt](src/main/kotlin/com/meow/mod/features/AutoExperiments.kt))
are ports of modules from [skies-starred/OdinClient](https://github.com/skies-starred/OdinClient),
rewritten in Kotlin against the menus this mod reads. Their notice, as required:

```
BSD 3-Clause License

Copyright (c) 2026, Starred

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## V5 — GNU GPL-3.0

`No Interact` ([NoInteract.kt](src/main/kotlin/com/meow/mod/features/NoInteract.kt)) re-implements
the "Cancel Interact" module from [V5-Client/V5](https://github.com/V5-Client/V5), which is licensed
GPL-3.0. The rules were written out again in Kotlin rather than copied, but the behaviour is theirs,
and the reference is pinned here:

<https://github.com/V5-Client/V5/blob/55ae528a57c1bc06bb1f3ff6c474ed830990e7e9/modules/other/CancelInteract.js>

That module is GPL-3.0, so that one file is not covered by this project's MIT licence: treat
`NoInteract.kt` as GPL-3.0 if you reuse it.
