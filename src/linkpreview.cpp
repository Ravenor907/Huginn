#include "linkpreview.h"
#include "morewire.h"
#include "photos.h"

#include <QBuffer>
#include <QCoreApplication>
#include <QImage>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <QRegularExpression>
#include <QUrl>
#include <cstdio>
#include <memory>

namespace
{
const int kPageMax = 512 * 1024;
const int kImageMax = 4 * 1024 * 1024;
const int kThumbSide = 240;
const int kThumbBytes = 16 * 1024;

// Its own connection pool: unlike Chat's (which talks to devices with their own certificates), this one
// checks every certificate the normal way.
QNetworkAccessManager *web()
{
    static QPointer<QNetworkAccessManager> nam;
    if (!nam)
        nam = new QNetworkAccessManager(QCoreApplication::instance());
    return nam;
}

// The start of [url] (at most [max] bytes), when it answers with a content type starting [type]; empty otherwise.
void get(const QString &url, int max, const QString &type, QObject *ctx, std::function<void(const QByteArray &)> done)
{
    if (!url.startsWith(QLatin1String("https://")) && !url.startsWith(QLatin1String("http://"))) {
        done({});
        return;
    }
    QNetworkRequest req{QUrl(url)};
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setTransferTimeout(6000);
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Mozilla/5.0 (compatible; link preview)"));
    req.setRawHeader("Accept", type.endsWith(QLatin1Char('/')) ? QByteArray("image/*") : type.toLatin1());
    QNetworkReply *r = web()->get(req);
    auto buf = std::make_shared<QByteArray>();
    auto over = std::make_shared<bool>(false);
    auto end = [r, buf, over, type, done] {
        if (*over)
            return;
        *over = true;
        const int code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QString ct = r->header(QNetworkRequest::ContentTypeHeader).toString().trimmed().toLower();
        const bool good = code >= 200 && code < 300 && ct.startsWith(type) && !buf->isEmpty();
        r->deleteLater();
        done(good ? *buf : QByteArray());
    };
    QObject::connect(r, &QNetworkReply::readyRead, ctx, [r, buf, max, end] {
        buf->append(r->read(max - buf->size()));
        if (buf->size() >= max) {
            end();   // the head of a page is enough; a huge picture isn't worth it
            r->abort();
        }
    });
    QObject::connect(r, &QNetworkReply::finished, ctx, [r, buf, max, end, over] {
        if (*over)
            return;
        if (r->error() != QNetworkReply::NoError)
            buf->clear();
        else
            buf->append(r->read(max - buf->size()));
        end();
    });
}

QString attr(const QString &tag, const QString &name)
{
    const QRegularExpression re(QStringLiteral("\\b%1\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").arg(name), QRegularExpression::CaseInsensitiveOption);
    const auto m = re.match(tag);
    if (!m.hasMatch())
        return {};
    return m.captured(2).isEmpty() ? m.captured(3) : m.captured(2);
}

QString unescape(QString s)
{
    s.replace(QLatin1String("&amp;"), QLatin1String("&")).replace(QLatin1String("&quot;"), QLatin1String("\"")).replace(QLatin1String("&#39;"), QLatin1String("'"))
        .replace(QLatin1String("&apos;"), QLatin1String("'")).replace(QLatin1String("&lt;"), QLatin1String("<")).replace(QLatin1String("&gt;"), QLatin1String(">"));
    static const QRegularExpression num(QStringLiteral("&#(\\d+);"));
    QString out;
    qsizetype at = 0;
    for (auto it = num.globalMatch(s); it.hasNext();) {
        const auto m = it.next();
        out += s.mid(at, m.capturedStart() - at);
        const uint c = m.captured(1).toUInt();
        if (c > 0 && c < 0x110000)
            out += QString::fromUcs4(reinterpret_cast<const char32_t *>(&c), 1);
        at = m.capturedEnd();
    }
    return out + s.mid(at);
}

// <meta property="og:title" content="..."> or name="...", in either order.
QString meta(const QString &page, const QString &key)
{
    static const QRegularExpression tag(QStringLiteral("<meta\\b[^>]*>"), QRegularExpression::CaseInsensitiveOption);
    for (auto it = tag.globalMatch(page); it.hasNext();) {
        const QString t = it.next().captured(0);
        QString k = attr(t, QStringLiteral("property"));
        if (k.isEmpty())
            k = attr(t, QStringLiteral("name"));
        if (k.compare(key, Qt::CaseInsensitive) == 0) {
            const QString c = unescape(attr(t, QStringLiteral("content")));
            return c.trimmed().isEmpty() ? QString() : c;
        }
    }
    return {};
}

QString title(const QString &page)
{
    static const QRegularExpression re(QStringLiteral("<title\\b[^>]*>(.*?)</title>"), QRegularExpression::CaseInsensitiveOption | QRegularExpression::DotMatchesEverythingOption);
    const auto m = re.match(page);
    return m.hasMatch() ? unescape(m.captured(1)) : QString();
}

// One line, no control characters, at most [max] code points.
QString clean(const QString &s, int max)
{
    static const QRegularExpression space(QStringLiteral("\\s+"));
    QString one = QString(s).replace(space, QStringLiteral(" "));
    QString kept;
    for (const QChar c : std::as_const(one))
        if (c.unicode() >= 0x20 && c.unicode() != 0x7F)
            kept += c;
    kept = kept.trimmed();
    const QList<uint> cps = kept.toUcs4();
    if (cps.size() <= max)
        return kept;
    return QString::fromUcs4(reinterpret_cast<const char32_t *>(cps.constData()), max - 1).trimmed() + QStringLiteral("…");
}
}

QJsonObject LinkPreview::fromPage(const QString &url, const QString &html, QString *image)
{
    QString t = meta(html, QStringLiteral("og:title"));
    if (t.isEmpty())
        t = meta(html, QStringLiteral("twitter:title"));
    if (t.isEmpty())
        t = title(html);
    t = clean(t, MoreWire::kTitleMax);
    if (t.isEmpty())
        return {};
    QString d = meta(html, QStringLiteral("og:description"));
    if (d.isEmpty())
        d = meta(html, QStringLiteral("description"));
    d = clean(d, MoreWire::kDescMax);
    QJsonObject lp{{QStringLiteral("u"), url}, {QStringLiteral("t"), t}};
    if (!d.isEmpty())
        lp.insert(QStringLiteral("d"), d);
    if (image) {
        const QString img = meta(html, QStringLiteral("og:image")).trimmed();
        *image = img.isEmpty() ? QString() : QUrl(url).resolved(QUrl(img)).toString();
    }
    return lp;
}

QByteArray LinkPreview::thumbnail(const QByteArray &picture)
{
    QImage img = QImage::fromData(picture);
    if (img.isNull())
        return {};
    if (qMax(img.width(), img.height()) > kThumbSide)
        img = img.scaled(kThumbSide, kThumbSide, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    img = img.convertToFormat(QImage::Format_RGB32);
    for (int q : {70, 55, 40}) {
        QByteArray out;
        QBuffer b(&out);
        b.open(QIODevice::WriteOnly);
        img.save(&b, "JPEG", q);
        if (!out.isEmpty() && out.size() <= kThumbBytes)
            return Photos::scrub(out);
    }
    return {};
}

void LinkPreview::fetch(const QString &url, QObject *ctx, std::function<void(const QJsonObject &)> done)
{
    // Checked as the other side will: a preview that wouldn't pass isn't sent.
    auto checked = [url, done](QJsonObject lp) {
        const QJsonObject p{{QStringLiteral("text"), url}, {QStringLiteral("lp"), lp}};
        done(!lp.isEmpty() && MoreWire::ok(QStringLiteral("rich"), p) ? lp : QJsonObject());
    };
    get(url, kPageMax, QStringLiteral("text/html"), ctx, [url, ctx, checked](const QByteArray &page) {
        if (page.isEmpty()) {
            checked({});
            return;
        }
        QString image;
        QJsonObject lp = fromPage(url, QString::fromUtf8(page), &image);
        if (lp.isEmpty() || image.isEmpty()) {
            checked(lp);
            return;
        }
        get(image, kImageMax, QStringLiteral("image/"), ctx, [lp, checked](const QByteArray &pic) mutable {
            const QByteArray thumb = pic.isEmpty() ? QByteArray() : thumbnail(pic);
            if (!thumb.isEmpty())
                lp.insert(QStringLiteral("i"), QString::fromLatin1(thumb.toBase64()));
            checked(lp);
        });
    });
}

bool LinkPreview::selfTest()
{
    QString image;
    const QJsonObject a = fromPage(QStringLiteral("https://example.com/a/b"),
                                   QStringLiteral("<html><head><title>Plain</title><meta content=\"A &amp; B\" property=\"og:title\">"
                                                  "<meta name='description' content='Two\n lines &#65;'><meta property=\"og:image\" content=\"/p.jpg\"></head></html>"),
                                   &image);
    const QJsonObject b = fromPage(QStringLiteral("https://example.com"), QStringLiteral("<TITLE>\n  Only a title </TITLE>"), &image);
    const QJsonObject c = fromPage(QStringLiteral("https://example.com"), QStringLiteral("<p>nothing</p>"), nullptr);
    const bool ok = a.value(QStringLiteral("t")).toString() == QLatin1String("A & B") && a.value(QStringLiteral("d")).toString() == QLatin1String("Two lines A")
        && b.value(QStringLiteral("t")).toString() == QLatin1String("Only a title") && !b.contains(QStringLiteral("d")) && image.isEmpty() && c.isEmpty();
    QString img2;
    fromPage(QStringLiteral("https://example.com/a/b"), QStringLiteral("<title>x</title><meta property=\"og:image\" content=\"/p.jpg\">"), &img2);
    const bool resolved = img2 == QLatin1String("https://example.com/p.jpg");
    std::printf("link preview self-test: %s\n", ok && resolved ? "passed" : "FAILED");
    return ok && resolved;
}
