# Main hall watercolor artwork

- Asset: `monastery.png`, replaced September 28, 2026 at the user's request.
- Created with the built-in ImageGen tool using an actual exterior photograph as an architectural reference.
- Style: watercolor and sepia ink on warm textured paper.
- Depicts the main meditation hall's historical red timber facade, twin steep red roofs, arched windows, pond garden, Buddha statue, and wooden footbridge.
- Reference: a Bhavana Society courtesy photograph captioned “The Bhavana Society’s lily pond and main meditation hall” in [William & Mary's Buddhism in America article](https://americanbuddhism.pages.wm.edu/bhavana-society/).
- [Direct reference photograph](https://s3.amazonaws.com/wmit-pages-prod/wp-content/uploads/sites/158/2017/11/20155038/PT01.jpg). This is a historical architectural reference, not a claim about the building's current paint or roofing.
- The photograph is a reference only; the finished painting is generated artwork.
- The app keeps the roofline visible, with no accompanying portrait or caption.
- The image is bundled locally in the JAR and Mac preview; the app makes no image network requests.

## Final generation prompt

Use case: illustration-story.
Asset type: panoramic architectural watercolor for a desktop app.
Primary request: an artful representation of Bhavana Society's actual MAIN MEDITATION HALL, faithfully based on the supplied photograph. This reference, with red timber walls and red roofs, is the building to depict.
Input image 1 is the sole architectural reference. Match its recognizable building rather than substituting a generic monastery: TWO steep, offset red gabled roof masses, larger taller roof on right and smaller projecting gable to its left; red-brown rounded horizontal log siding; narrow tall white-trimmed windows with semicircular arched tops; modest connecting wing toward the far left. Preserve the same three-quarter exterior view, the different roof heights, wooded setting, garden/lily pond with rocks and a small seated Buddha left of the building, and the little arched wooden footbridge in the lower right.
Composition: a wide horizontal landscape illustration, 3:1 aspect ratio (approximately 2304x768). Reframe the supplied photograph by painting outward into the surrounding forest and garden, with the entire distinctive pair of gabled roofs visible. Keep the recognizable building and garden centrally composed, with loose trees and open paper at the edges. Keep all roof peaks comfortably inside the canvas; no cropping of the roof. Do not add new architectural wings to fill the panoramic width.
Style: fine-art watercolor with delicate sepia ink drawing on warm textured cotton paper. Confident loose brushwork in trees, soft transparent washes, pigment granulation, warm brick-red and russet timber, gentle sage and moss greens, quiet gold sunlight, soft blue-gray shadows. Observed architecture with lively hand-drawn contours; expressive but faithful. Clearly a painting, not a photograph or a photo filter. Slightly softened romantic atmosphere, inviting and contemplative.
Constraints: no people, no lettering, no signature, no watermark, no ornate pagodas, no cream suburban building, no invented columns or front porch, no green roof. Retain the red roof and red timber identity of the supplied historical reference. Do not use any previous generated image as the building reference.

## Wider banner revision — September 28, 2026

The user requested the same painting remade about 1.3 cm wider. The built-in ImageGen tool edited the existing artwork, extending the side foliage while retaining its red timber monastery, two gable roofs, pond, small Buddha and bridge. The accepted output is bundled as `monastery.png`; the prior image is retained in `docs/monastery-original.png`.

Final prompt:

> Edit this existing watercolor, preserving it almost exactly, to make a SLIGHTLY WIDER panoramic banner. Output must be an extremely wide, short landscape canvas, aspect ratio 3:1, 2304 x 768 pixels. Do not output a 2:1 image. Keep the red timber monastery with its same two red steep gable roofs, garden, small seated Buddha, arched wooden bridge, tree trunks, pale cream ragged watercolor edges. Do not change perspective, architecture, color, or style. Add approximately 8 percent more landscape horizontally at the two side edges, foliage and garden only, like a panorama extension. Keep original image's very close upper crop: the higher red roof peak should sit only 35 pixels below the upper image edge (about 5 percent of image height). Keep same close lower crop through pond and bridge. No extra vertical sky or paper margin. No text. Same serene watercolor and sepia pen drawing. This is to replace the given banner illustration at about 1.3cm wider on screen.

Actual output: 2172 × 724 pixels. At this revision the GUI displayed the painting at 679 logical pixels wide in the usual 210-pixel banner, up from 630. The extra 49 logical pixels approximate 1.3 cm at 96 DPI; physical size varies with the display. Aspect ratio is preserved, with the small vertical overflow clipped by the banner. The painting remains centered and the green fade overlaps its left edge.

## Reduced right margin

The subsequent banner revision fills the area from the green fade to a 12-pixel right inset with the same painting. The image scales proportionally and is anchored at the top to keep both roof peaks visible. The lower foreground is clipped within the rounded banner. This replaces the fixed-width placement that left a broad beige gap on the right.
