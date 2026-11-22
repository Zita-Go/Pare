# Step 材质提案生成记录

状态：设计评审稿，未批准接入。使用内置 imagegen；未使用 CLI 或用户的模型 API 密钥。

第一张参考为本项目 B03 轮廓；第二张为用户提供的 Dock 图标截图，仅用于材质与光影参考，未将该截图复制进仓库。第三次生成以上一轮生成图作为编辑目标。

## 01 材质探索

```text
Use case: style-transfer / premium app icon design.
Image 1 is the edit target: an original Step app icon with a single rising stair / angular S symbol. Image 2 is ONLY a material-and-finish reference showing a macOS Dock; do not reproduce any of those logos or the Dock.
Refine Image 1 into one mature, professionally art-directed macOS/Android app icon. Keep its distinctive single-step silhouette: lower horizontal tread, vertical rise, upper horizontal tread, balanced lengths, small controlled corners, flat-ended terminals. Preserve this simple geometry and centered composition.
Give the foreground warm porcelain / pearl-white satin material with a very shallow bevel and a restrained, crisp edge highlight. A subtle lower-right contact shadow makes it float slightly over the tile. Give the rounded-square tile a rich emerald-to-deep-teal gradient, brighter in its upper left and darker at lower right; restrained glassy surface illumination and a precise thin lit rim. This should have the crafted depth and color richness of polished mainstream desktop icons while remaining a crisp recognizable symbol at 32 pixels.
Single icon, straight-on orthographic view. Strong balanced negative space. No text, no labels, no presentation board, no copied logos, no notification badge. Do not add dots in this variant. Avoid inflated clay, thick gummy tube, cartoon character, excessive bevels, sparkling starbursts, rainbow colors, photographic texture, or a literal architectural staircase. Keep genuine transparent corners outside the rounded-square icon. High-resolution finished design proposal.
```

## 02 路径与目标探索

```text
Use case: style-transfer / premium app icon design.
Image 1 is the edit target and identity starting point: an original Step icon shaped like one rising stair / angular S. Image 2 is ONLY a reference for the rich gradients, soft gloss, material depth and compact visual polish of mature desktop app icons; do not copy any existing logo or reproduce the Dock.
Create one sophisticated Step app icon exploring "advance along a path and reach a goal". Evolve the existing single-step silhouette into a continuous sculpted route with two disciplined bends, moving from lower left to upper right. Preserve the overall stair/S identity and readable simple silhouette. The upper endpoint stops a short distance before ONE small amber-gold goal sphere; the gap must clearly separate the destination from the route. The goal sphere is within the composition, not a notification badge. This subtly references the movement-and-target idea of Snake, without a snake character, eyes, face or game board.
Use a luminous jade/ivory path with a refined satin-glass finish, faint cool edge reflections and shallow depth, on a rounded-square midnight-teal tile with a soft controlled jade gradient. The amber goal has a restrained specular highlight. Use one consistent upper-left light source, gentle contact shadows and clean crisp edges. Warm and inviting but designed as a professional planning/goal app, not a toy or game.
Single icon, straight-on orthographic view, centered, well-balanced breathing room. No text, no label, no presentation board, no copied logos. No extra dots, stars, arrows, graphs, extra coils, cartoon eyes, thick inflatable gummy tube or excessive glow. Maintain genuine transparency outside the rounded square. High-resolution finished design proposal.
```

## 03 建议评审稿的最终提示词

```text
Use case: precise-object-edit / premium Step app icon refinement.
Edit this exact icon. Preserve the stable, flat-ended stair/S silhouette, the emerald-teal color family, the orthographic front view, the rounded-square footprint and its genuine transparent exterior.
Make only these refinements:
1. Add ONE small amber-gold destination sphere to the right of the upper horizontal endpoint. Its center must be on exactly the SAME HORIZONTAL CENTERLINE as that upper bar, not above it and not at the tile corner. In the 1280 px reference, the bar center is around y=404; place the target near x=1080,y=404. Sphere diameter approximately 78 px, clearly smaller than the 120 px bar thickness. Leave a visible gap of roughly 45–60 px between endpoint and sphere. It represents a goal at the end of a route, never a notification badge.
2. Keep the mark's structural, flat-ended geometry. Make its pearl-white shallow relief and bevel slightly subtler, with one clean upper-left highlight and a quiet contact shadow, not a puffy or gummy surface.
3. Reduce the bright outer rim, reflected green halo and glass-edge lighting by about 60%. Retain a smooth, rich emerald-to-midnight-teal gradient and controlled upper-left illumination. Preserve material depth while making the whole tile look calmer and more contemporary.
4. The small goal sphere should be satin amber with one restrained highlight, no surrounding yellow glow.
No other elements. No text, no labels, no faces, no arrows, no extra dots, no altered staircase route, no new frame. Finished high-resolution app-icon design proposal on genuinely transparent exterior.
```
