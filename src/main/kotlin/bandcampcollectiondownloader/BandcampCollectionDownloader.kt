package bandcampcollectiondownloader

import com.google.gson.Gson
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.zeroturnaround.zip.ZipUtil
import retrieveCookiesFromFile
import retrieveFirefoxCookies
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern


data class ParsedBandcampData(
        @Suppress("ArrayInDataClass") val digital_items: Array<DigitalItem>
)

data class DigitalItem(
        val downloads: Map<String, Map<String, String>>,
        val package_release_date: String,
        val title: String,
        val artist: String,
        val download_type: String,
        val art_id: String
)

data class ParsedStatDownload(
        val download_url: String
)


/**
 * Core function called from the main
 */
fun downloadAll(cookiesFile: Path?, bandcampUser: String, downloadFormat: String, downloadFolder: Path, retries: Int) {
    val gson = Gson()
    val cookies =

            if (cookiesFile != null) {
                // Parse JSON cookies (obtained with "Cookie Quick Manager" Firefox addon)
                println("Loading provided cookies file: $cookiesFile")
                retrieveCookiesFromFile(cookiesFile, gson)
            } else {
                // Try to find cookies stored in default firefox profile
                println("No provided cookies file, using Firefox cookies.")
                retrieveFirefoxCookies()
            }

    // Get collection page with cookies, hence with download links
    val doc = try {
        Jsoup.connect("https://bandcamp.com/$bandcampUser")
                .cookies(cookies)
                .get()
    } catch (e: HttpStatusException) {
        if (e.statusCode == 404) {
            throw BandCampDownloaderError("The bandcamp user '$bandcampUser' does not exist.")
        } else {
            throw e
        }
    }
    println("""Found collection page: "${doc.title()}"""")

    // Get download pages
    val collection = doc.select("span.redownload-item a")

    if (collection.isEmpty()) {
        throw BandCampDownloaderError("No download links could by found in the collection page. This can be caused by an outdated or invalid cookies file.")
    }

    // For each download page
    for (item in collection) {
        val downloadPageURL = item.attr("href")
        val downloadPageJsonParsed = getDataBlobFromDownloadPage(downloadPageURL, cookies, gson)

        // Extract data from blob
        val digitalItem = downloadPageJsonParsed.digital_items[0]
        var albumtitle = digitalItem.title
        var artist = digitalItem.artist
        val releaseDate = digitalItem.package_release_date
        val releaseYear = releaseDate.subSequence(7, 11)
        val isSingleTrack: Boolean = digitalItem.download_type == "t"
        val url = digitalItem.downloads[downloadFormat]?.get("url").orEmpty()
        val artid = digitalItem.art_id

        // If windows, replace colons in file names by a unicode char that looks like a colon
        if (isWindows()) {
            albumtitle = albumtitle.replace(':', '꞉')
            artist = artist.replace(':', '꞉')
        }

        // Prepare artist and album folder
        val albumFolderName = "$releaseYear - $albumtitle"
        val artistFolderPath = Paths.get("$downloadFolder").resolve(artist)
        val albumFolderPath = artistFolderPath.resolve(albumFolderName)

        // Download album, with as many retries as configured
        val attempts = retries + 1
        for (i in 1..attempts) {
            if (i > 1) {
                println("Retrying download (${i - 1}/$retries).")
            }
            try {
                downloadAlbum(artistFolderPath, albumFolderPath, albumtitle, url, cookies, gson, isSingleTrack, artid)
            } catch (e: Throwable) {
                println("""Error while downloading: "${e.javaClass.name}: ${e.message}".""")
                if (i == attempts) {
                    throw BandCampDownloaderError("Could not download album after $retries retries.")
                }
            }
        }
    }
}


class BandCampDownloaderError(s: String) : Exception(s)

fun downloadAlbum(artistFolderPath: Path?, albumFolderPath: Path, albumtitle: String, url: String, cookies: Map<String, String>, gson: Gson, isSingleTrack: Boolean, artid: String) {
    // If the artist folder does not exist, we create it
    if (!Files.exists(artistFolderPath)) {
        Files.createDirectories(artistFolderPath)
    }

    // If the album folder does not exist, we create it
    if (!Files.exists(albumFolderPath)) {
        Files.createDirectories(albumFolderPath)
    }

    // If the folder is empty, or if it only contains the zip.part file, we proceed
    val amountFiles = albumFolderPath.toFile().listFiles().size
    if (amountFiles < 2) {

        val outputFilePath: Path = prepareDownload(albumtitle, url, cookies, gson, albumFolderPath)

        // If this is a zip, we unzip
        if (!isSingleTrack) {

            // Unzip
            try {
                ZipUtil.unpack(outputFilePath.toFile(), albumFolderPath.toFile())
            } finally {
                // Delete zip
                Files.delete(outputFilePath)
            }
        }

        // Else if this is a single track, we just fetch the cover
        else {
            val coverURL = "https://f4.bcbits.com/img/a${artid}_10"
            println("Downloading cover ($coverURL)...")
            downloadFile(coverURL, albumFolderPath, "cover.jpg")
        }

        println("done.")

    } else {
        println("Album $albumtitle already done, skipping")
    }
}

fun getDataBlobFromDownloadPage(downloadPageURL: String?, cookies: Map<String, String>, gson: Gson): ParsedBandcampData {
    println("Analyzing download page $downloadPageURL")

    // Get page content
    val downloadPage = Jsoup.connect(downloadPageURL)
            .cookies(cookies)
            .timeout(100000).get()

    // Get data blob
    val downloadPageJson = downloadPage.select("#pagedata").attr("data-blob")
    return gson.fromJson(downloadPageJson, ParsedBandcampData::class.java)
}

fun prepareDownload(albumtitle: String, url: String, cookies: Map<String, String>, gson: Gson, albumFolderPath: Path): Path {
    println("Preparing download of $albumtitle ($url)...")

    val random = Random()

    // Construct statdownload request URL
    val statdownloadURL: String = url
            .replace("/download/", "/statdownload/")
            .replace("http", "https") + "&.vrs=1" + "&.rand=" + random.nextInt()

    // Get statdownload JSON
    println("Getting download link ($statdownloadURL)")
    val statedownloadUglyBody: String = Jsoup.connect(statdownloadURL)
            .cookies(cookies)
            .timeout(100000)
            .get().body().select("body")[0].text().toString()

    val prefixPattern = Pattern.compile("""if\s*\(\s*window\.Downloads\s*\)\s*\{\s*Downloads\.statResult\s*\(\s*""")
    val suffixPattern = Pattern.compile("""\s*\)\s*};""")
    val statdownloadJSON: String =
            prefixPattern.matcher(
                    suffixPattern.matcher(statedownloadUglyBody)
                            .replaceAll("")
            ).replaceAll("")

    // Parse statdownload JSON and get real download URL, and retrieve url
    val statdownloadParsed: ParsedStatDownload = gson.fromJson(statdownloadJSON, ParsedStatDownload::class.java)
    val realDownloadURL = statdownloadParsed.download_url

    println("Downloading $albumtitle ($realDownloadURL)")

    // Download content
    return downloadFile(realDownloadURL, albumFolderPath)
}