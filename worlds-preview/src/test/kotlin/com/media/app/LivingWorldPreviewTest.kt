package com.media.app

import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/**
 * Renders every world to worlds-preview/build/living-worlds/: a still on the kick and
 * between kicks, a two-second loop as a GIF, and one contact sheet of all
 * seven. The Living Worlds workflow uploads the folder, so every push shows
 * what the worlds look like. The drawing is LivingWorld.kt's own; only the
 * Now Playing around it is a stand-in (WorldStage).
 */
class LivingWorldPreviewTest {
    private val out = File("build/living-worlds").apply { mkdirs() }

    @Test
    fun renderPreviews() {
        val w = 540
        val h = 1200
        val stills = mutableListOf<Pair<String, BufferedImage>>()
        for (kind in WorldKind.entries) {
            val name = kind.name.lowercase()
            val onKick = WorldStage.render(kind, w, h, 6.03f)
            val between = WorldStage.render(kind, w, h, 6.38f)
            ImageIO.write(onKick, "png", File(out, "$name-kick.png"))
            ImageIO.write(between, "png", File(out, "$name-between.png"))
            stills += kind.name to onKick
            writeGif(kind, File(out, "$name.gif"))
        }
        ImageIO.write(sheet(stills, w, h), "png", File(out, "all-worlds.png"))
        println("Living Worlds previews written to ${out.absolutePath}")
    }

    /** All seven side by side, a third of the size, labelled. */
    private fun sheet(stills: List<Pair<String, BufferedImage>>, w: Int, h: Int): BufferedImage {
        val tw = w / 2
        val th = h / 2
        val gap = 12
        val label = 40
        val sheet = BufferedImage(stills.size * (tw + gap) + gap, th + label + gap * 2, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x050608)
        g.fillRect(0, 0, sheet.width, sheet.height)
        stills.forEachIndexed { i, (name, img) ->
            val x = gap + i * (tw + gap)
            g.drawImage(img, x, gap + label, tw, th, null)
            g.color = Color(0xF4, 0xEF, 0xE6)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
            runCatching { g.drawString(name.replace('_', ' '), x + 4, gap + 26) }
        }
        g.dispose()
        return sheet
    }

    /** Two seconds of the world at 15 fps, 270 x 600, looping. */
    private fun writeGif(kind: WorldKind, file: File) {
        val w = 270
        val h = 600
        val frames = 30
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        file.delete()
        FileImageOutputStream(file).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            for (i in 0 until frames) {
                val img = WorldStage.render(kind, w, h, 5f + i / 15f)
                val type = ImageTypeSpecifier.createFromRenderedImage(img)
                val meta = writer.getDefaultImageMetadata(type, null)
                val format = meta.nativeMetadataFormatName
                val root = meta.getAsTree(format) as IIOMetadataNode
                child(root, "GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", "7")
                    setAttribute("transparentColorIndex", "0")
                }
                if (i == 0) {
                    val apps = child(root, "ApplicationExtensions")
                    val app = IIOMetadataNode("ApplicationExtension")
                    app.setAttribute("applicationID", "NETSCAPE")
                    app.setAttribute("authenticationCode", "2.0")
                    app.userObject = byteArrayOf(1, 0, 0)
                    apps.appendChild(app)
                }
                meta.setFromTree(format, root)
                writer.writeToSequence(IIOImage(img, null, meta), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    private fun child(root: IIOMetadataNode, name: String): IIOMetadataNode {
        for (i in 0 until root.length) {
            val n = root.item(i)
            if (n.nodeName.equals(name, ignoreCase = true)) return n as IIOMetadataNode
        }
        return IIOMetadataNode(name).also { root.appendChild(it) }
    }
}
