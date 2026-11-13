# Avvisi di terze parti (open source)

Ultima revisione: 2026-05-06

Questo documento riguarda software e risorse open source usati dall'app orologio (`app`) e dall'app companion (`glancemapcompanionapp`).

## Componenti open source principali

| Progetto / famiglia | Componenti usati (esempi) | Licenza | Fonte |
|---|---|---|---|
| AndroidX + Jetpack Compose + Wear Compose | `androidx.*` (Compose UI/Foundation/Material, Navigation, Lifecycle, DataStore, Wear) | Apache License 2.0 | https://github.com/androidx/androidx |
| Kotlin | `org.jetbrains.kotlin:*` | Apache License 2.0 | https://github.com/JetBrains/kotlin |
| Kotlin Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-*` | Apache License 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| Ktor | `io.ktor:*` | Apache License 2.0 | https://github.com/ktorio/ktor |
| OkHttp | `com.squareup.okhttp3:okhttp*` | Apache License 2.0 | https://github.com/square/okhttp |
| Okio | `com.squareup.okio:okio*` | Apache License 2.0 | https://github.com/square/okio |
| Gson | `com.google.code.gson:gson` | Apache License 2.0 | https://github.com/google/gson |
| Accompanist | `com.google.accompanist:*` | Apache License 2.0 | https://github.com/google/accompanist |
| AndroidSVG | `com.caverock:androidsvg` | Apache License 2.0 | https://github.com/BigBadaboom/androidsvg |
| WorldWind Kotlin | `earth.worldwind:worldwind:2.1.1`, `earth.worldwind:worldwind-assets:1.0.0` | Apache License 2.0; bundled GLU code includes the licence below | https://github.com/WorldWindEarth/WorldWindKotlin |
| Mapsforge | `com.github.mapsforge.mapsforge:*` (`mapsforge-map`, `mapsforge-map-android`, `mapsforge-themes`, ecc.) | LGPL-3.0-or-later (con deroga al collegamento statico/opere derivate dichiarata da Mapsforge nel README) | https://github.com/mapsforge/mapsforge |
| MapLibre Native Android | `org.maplibre.gl:*` (`android-sdk`, `android-sdk-geojson`, `android-sdk-turf`, `maplibre-android-gestures`) | BSD 2-Clause License | https://github.com/maplibre/maplibre-native |
| BRouter | Moduli inclusi `:brouter-core`, `:brouter-codec`, `:brouter-expressions`, `:brouter-mapaccess`, `:brouter-util` da `third_party/brouter/*` | MIT License | https://github.com/abrensch/brouter |
| kXML2 | `net.sf.kxml:kxml2` | Stile BSD / molto permissiva (secondo progetto e metadati POM) | https://kobjects.org/kxml/ |
| Icone Maki | Icone SVG POI da Mapbox Maki | CC0 1.0 Universal | https://github.com/mapbox/maki |
| Icone Temaki | Icone SVG POI da Temaki | CC0 1.0 Universal | https://github.com/rapideditor/temaki |

## Dipendenza d'esecuzione importante non open source

- I componenti Google Play services, ad esempio `com.google.android.gms:play-services-wearable` e `play-services-location`, sono distribuiti secondo condizioni Google/Android SDK, non licenze open source standard.
- Riferimenti alle condizioni:
  - https://developer.android.com/studio/terms
  - https://developers.google.com/android/guides/opensource

## Inventario

L'inventario risolto delle coordinate delle dipendenze d'esecuzione è incluso in:

- `RUNTIME_DEPENDENCIES_RESOLVED.txt`

Questo file aiuta la revisione interna delle dipendenze transitive oltre alle famiglie principali sopra elencate.

## Note di conformità

- Mantenere attribuzioni Apache-2.0 e riferimenti licenza nella documentazione distribuita con l'app.
- Per Mapsforge (famiglia LGPL), conservare gli avvisi e rivedere gli obblighi del modello di distribuzione.
- Per MapLibre Native (BSD 2-Clause), conservare l'avviso copyright/licenza nella documentazione distribuita con i binari.
- Ricontrollare le modifiche alle licenze originali aggiornando le dipendenze.

## WorldWind Kotlin

Copyright 2022 WorldWind Community Edition contributors.

### Apache License 2.0


                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.


### WorldWind GLU third-party licence

JOGL is released under the BSD license. The full license terms follow:

   Copyright (c) 2003-2007 Sun Microsystems, Inc. All Rights Reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are
   met:

   - Redistribution of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.

   - Redistribution in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.

   Neither the name of Sun Microsystems, Inc. or the names of
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

   This software is provided "AS IS," without a warranty of any kind. ALL
   EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
   INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
   PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
   MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
   ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
   DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
   ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
   DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
   DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
   ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
   SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.

   You acknowledge that this software is not designed or intended for use
   in the design, construction, operation or maintenance of any nuclear
   facility.

The JOGL source tree contains code ported from the OpenGL sample
implementation by Silicon Graphics, Inc. This code is licensed under
the SGI Free Software License B (Sun is redistributing the modified code
under a slightly modified, alternative license, which is described two
paragraphs below after "NOTE:"):

   License Applicability. Except to the extent portions of this file are
   made subject to an alternative license as permitted in the SGI Free
   Software License B, Version 1.1 (the "License"), the contents of this
   file are subject only to the provisions of the License. You may not use
   this file except in compliance with the License. You may obtain a copy
   of the License at Silicon Graphics, Inc., attn: Legal Services, 1600
   Amphitheatre Parkway, Mountain View, CA 94043-1351, or at:

   http://oss.sgi.com/projects/FreeB

   Note that, as provided in the License, the Software is distributed on an
   "AS IS" basis, with ALL EXPRESS AND IMPLIED WARRANTIES AND CONDITIONS
   DISCLAIMED, INCLUDING, WITHOUT LIMITATION, ANY IMPLIED WARRANTIES AND
   CONDITIONS OF MERCHANTABILITY, SATISFACTORY QUALITY, FITNESS FOR A
   PARTICULAR PURPOSE, AND NON-INFRINGEMENT.

   NOTE:  The Original Code (as defined below) has been licensed to Sun
   Microsystems, Inc. ("Sun") under the SGI Free Software License B
   (Version 1.1), shown above ("SGI License").   Pursuant to Section
   3.2(3) of the SGI License, Sun is distributing the Covered Code to
   you under an alternative license ("Alternative License").  This
   Alternative License includes all of the provisions of the SGI License
   except that Section 2.2 and 11 are omitted.  Any differences between
   the Alternative License and the SGI License are offered solely by Sun
   and not by SGI.

   Original Code. The Original Code is: OpenGL Sample Implementation,
   Version 1.2.1, released January 26, 2000, developed by Silicon Graphics,
   Inc. The Original Code is Copyright (c) 1991-2000 Silicon Graphics, Inc.
   Copyright in any portions created by third parties is as indicated
   elsewhere herein. All Rights Reserved.

   Additional Notice Provisions: The application programming interfaces
   established by SGI in conjunction with the Original Code are The
   OpenGL(R) Graphics System: A Specification (Version 1.2.1), released
   April 1, 1999; The OpenGL(R) Graphics System Utility Library (Version
   1.3), released November 4, 1998; and OpenGL(R) Graphics with the X
   Window System(R) (Version 1.3), released October 19, 1998. This software
   was created using the OpenGL(R) version 1.2.1 Sample Implementation
   published by SGI, but has not been independently verified as being
   compliant with the OpenGL(R) version 1.2.1 Specification.


The JOGL source tree contains code from the LWJGL project which is
similarly covered by the BSD license:

   Copyright (c) 2002-2004 LWJGL Project
   All rights reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are
   met:

   * Redistributions of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.

   * Redistributions in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.

   * Neither the name of 'LWJGL' nor the names of
     its contributors may be used to endorse or promote products derived
     from this software without specific prior written permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
   TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
   PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
   CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

The JOGL source tree also contains a Java port of Brian Paul's Tile
Rendering library, used with permission of the author under the BSD
license instead of the original LGPL:

   Copyright (c) 1997-2005 Brian Paul. All Rights Reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions are
   met:

   - Redistribution of source code must retain the above copyright
     notice, this list of conditions and the following disclaimer.

   - Redistribution in binary form must reproduce the above copyright
     notice, this list of conditions and the following disclaimer in the
     documentation and/or other materials provided with the distribution.

   Neither the name of Brian Paul or the names of contributors may be
   used to endorse or promote products derived from this software
   without specific prior written permission.

   This software is provided "AS IS," without a warranty of any
   kind. ALL EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND
   WARRANTIES, INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY,
   FITNESS FOR A PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY
   EXCLUDED. THE COPYRIGHT HOLDERS AND CONTRIBUTORS SHALL NOT BE
   LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING,
   MODIFYING OR DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO
   EVENT WILL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY
   LOST REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL,
   CONSEQUENTIAL, INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND
   REGARDLESS OF THE THEORY OF LIABILITY, ARISING OUT OF THE USE OF OR
   INABILITY TO USE THIS SOFTWARE, EVEN IF THE COPYRIGHT HOLDERS OR
   CONTRIBUTORS HAVE BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
