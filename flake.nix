{
  description = "A Nix-flake-based Scala development environment";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-24.11";
  };

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forEachSupportedSystem = f: nixpkgs.lib.genAttrs supportedSystems (system: f {
        pkgs = import nixpkgs { inherit system; overlays = [ self.overlays.default ]; };
      });
    in
    {
      overlays.default = final: prev:
        let
          javaPkg=prev.graalvm-ce;
          #javaPkg=prev.jdk23_headless;
        in
        {
          scala = prev.scala_3.override { jre = javaPkg; };
          scala-cli = prev.scala-cli.override { jre = javaPkg; };
          sbt = prev.sbt.override { jre = javaPkg; };
          bloop = prev.bloop.override { jre = javaPkg; };
          coursier = prev.coursier.override { jre = javaPkg; };
        };

      devShells = forEachSupportedSystem ({ pkgs }: {
        default = pkgs.mkShell {
          packages = with pkgs; [ 
            scala 
            scala-cli
            sbt 
            coursier 
            bloop
          ];
        };
      });
    };
}
