# Phone POI library

The Library's POIs tab follows the watch: files are grouped as My creation
(`my-places.poi`), GPX waypoints (`*__waypoints.poi`) and POI files, and a file
opens like a folder onto its category tree, read from the Mapsforge
`poi_categories` table. A single `root` category, as GlanceMap and Mapsforge
writers add, is dropped so its children become the top level. A category with
no sub-categories, or a file with only one category, lists its POIs; tapping a
POI centres the map on it, and places in My creation can be renamed or deleted.

Each file keeps its existing on/off switch. Categories can also be hidden per
file; they are stored with the hidden files as `file#categoryId` entries and
follow renames and deletes. The map's viewport query excludes POIs filed under a
hidden category in SQL, so hidden categories never use up the per-file viewport
limit. Showing a category also shows its file. Counts read `shown/total`, summed
from each category's direct POI count, so a POI filed under several categories
is counted in each.

The separate POI tools panel is no longer offered from the Library: its global
switch is the Layers POI setting, and file visibility, rename and delete live in
the Library.
