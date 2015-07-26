module simp {
    requires java.desktop;
    exports simp to java.desktop;
    provides javax.imageio.spi.ImageReaderSpi with simp.SIMPImageReaderSpi;
}
