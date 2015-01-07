class Duck < Formula
  homepage "https://duck.sh/"
  url "${SOURCE}"
  version "${VERSION}.${REVISION}"
  head "https://svn.cyberduck.io/trunk/"
  sha1 "${SOURCE.SHA1}"

  depends_on :java => ["1.7", :build]
  depends_on :xcode => :build
  depends_on "ant" => :build
  depends_on "openssl"

  def install
    system "ant", "-Dbuild.compile.target=1.7", "-Drevision=${REVISION}", "cli"
    system "install_name_tool", "-change", "/usr/lib/libcrypto.0.9.8.dylib", "/usr/local/opt/openssl/lib/libcrypto.dylib", "build/duck.bundle/Contents/Frameworks/libPrime.dylib"
    system "install_name_tool", "-change", "/usr/lib/libcrypto.0.9.8.dylib", "/usr/local/opt/openssl/lib/libcrypto.dylib", "build/duck.bundle/Contents/Frameworks/librococoa.dylib"
    libexec.install Dir["build/duck.bundle/*"]
    bin.install_symlink "#{libexec}/Contents/MacOS/duck" => "duck"
  end

  test do
    system "#{bin}/duck", "-version"
    filename = (testpath/"test")
    system "#{bin}/duck", "--download", stable.url, filename
    filename.verify_checksum stable.checksum
  end
end